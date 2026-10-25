/*
 * ptyrun — 在伪终端里运行一条命令，并把 pty 的输入输出接到自己的 stdin/stdout。
 *
 * 用途：本项目的 exploit 在拿到 root 后会 execl("/system/bin/sh", "sh", "-i")，
 * 而 "sh -i" 一旦发现没有 tty 就会立刻退出，导致注入的命令被外层 shell 接走。
 * Android 自带的 toybox 没有 script 命令，所以这里用不到 100 行代码顶替
 * busybox 的 "script -q -c CMD /dev/null"（省掉 1.4 MB 的 GPL 二进制）。
 *
 * 用法: ptyrun <cmd> [args...]
 *
 * 版权: Apache-2.0，见项目根目录 LICENSE。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static char buf[8192];

/* 把 from 读到的东西写进 to，返回 0 表示 EOF/错误 */
static int pump(int from, int to) {
    ssize_t n = read(from, buf, sizeof(buf));
    if (n <= 0) return 0;
    ssize_t off = 0;
    while (off < n) {
        ssize_t w = write(to, buf + off, (size_t)(n - off));
        if (w <= 0) {
            if (errno == EINTR) continue;
            return 0;
        }
        off += w;
    }
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: ptyrun <cmd> [args...]\n");
        return 2;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        perror("posix_openpt");
        return 1;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        perror("grantpt/unlockpt");
        return 1;
    }
    const char *slave_name = ptsname(master);
    if (!slave_name) {
        perror("ptsname");
        return 1;
    }

    /* 让子进程看到的终端是 80x24、无回显，避免日志里出现输入回显 */
    struct winsize ws = {.ws_row = 24, .ws_col = 80, .ws_xpixel = 0, .ws_ypixel = 0};
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        return 1;
    }

    if (pid == 0) {
        /* 子进程：新建会话并把 slave 设成控制终端 */
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        execvp(argv[1], &argv[1]);
        _exit(127);
    }

    /* 父进程：双向转发 stdin <-> master，直到子进程退出 */
    for (;;) {
        struct pollfd fds[2] = {
            {.fd = master, .events = POLLIN},
            {.fd = STDIN_FILENO, .events = POLLIN},
        };
        int r = poll(fds, 2, 500);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) {
            /* 超时：顺便看看子进程还在不在 */
            int st = 0;
            if (waitpid(pid, &st, WNOHANG) == pid) break;
            continue;
        }
        if (fds[0].revents & (POLLIN | POLLHUP)) {
            if (!pump(master, STDOUT_FILENO)) break;
        }
        if (fds[1].revents & (POLLIN | POLLHUP)) {
            if (!pump(STDIN_FILENO, master)) {
                /* stdin 关了：不再转发输入，但继续读输出直到子进程退出 */
                close(STDIN_FILENO);
                struct pollfd only[1] = {{.fd = master, .events = POLLIN}};
                while (poll(only, 1, -1) > 0) {
                    if (!pump(master, STDOUT_FILENO)) break;
                }
                break;
            }
        }
    }

    /* 收尾：把残余输出排空，回收子进程 */
    fcntl(master, F_SETFL, O_NONBLOCK);
    while (pump(master, STDOUT_FILENO)) {
    }
    int status = 0;
    waitpid(pid, &status, 0);
    close(master);

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
}
