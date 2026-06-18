/*
 * appsud -- permissive root command daemon for the DIY-rooted Rokid glasses.
 *
 * WHY A DAEMON (not a setuid binary):
 * On Android, zygote-spawned app processes run with an EMPTY capability bounding
 * set (CapBnd=0). A setuid-root binary can change uid to 0, but it can NEVER regain
 * CAP_SETUID/CAP_SETGID (they are not in the bounding set), so setresgid() fails with
 * EPERM and real root is unreachable from an app. This is exactly why Magisk and
 * similar use a root daemon + socket rather than setuid. We do the same.
 *
 * appsud is launched by init (diy-overlay.rc `service appsud`, seclabel su:s0) so it
 * runs as a real root process with full capabilities. It listens on an ABSTRACT unix
 * socket and runs each requested command as root via /system/bin/sh -c. There is NO
 * authentication: the device single-user-trusts on-device callers by design.
 *
 * STREAMING PROTOCOL (per connection, one command):
 *   client -> daemon:  the raw shell command bytes, then half-close (shutdown WR).
 *   daemon -> client:  a stream of frames emitted AS OUTPUT ARRIVES, until the command
 *                      exits, then a final exit frame. Each frame:
 *                          [1 byte type][4 byte big-endian length][length bytes payload]
 *                      type 1 = STDOUT chunk
 *                      type 2 = STDERR chunk
 *                      type 3 = EXIT  (payload = 4-byte big-endian int32 exit code)
 *                      type 4 = ERROR (payload = ASCII reason; daemon-side spawn failure)
 *                      then the daemon closes the connection.
 *
 * Both pipes are drained continuously with poll(), so a command that prints more than a
 * pipe buffer never blocks (the old buffer-everything model hung on >64 KiB before the
 * reader got to the second pipe). There is NO time limit: long-running commands stream
 * until they exit. Memory is bounded -- chunks are written straight to the socket, never
 * accumulated. One handler process is forked per connection so concurrent commands run
 * independently and a long command does not block new connections.
 *
 * Socket name (abstract namespace, leading NUL): "rokid_appsud".
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <fcntl.h>

#define SOCK_NAME   "rokid_appsud"
#define READ_CHUNK  65536
#define MAX_CMD     (1 * 1024 * 1024)

#define FRAME_STDOUT 1
#define FRAME_STDERR 2
#define FRAME_EXIT   3
#define FRAME_ERROR  4

static int write_all(int fd, const void *buf, size_t len) {
    const char *p = (const char *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n < 0) { if (errno == EINTR) continue; return -1; }
        p += n; len -= (size_t)n;
    }
    return 0;
}

/* Emit one framed message. Returns 0 on success, -1 if the socket write failed
 * (client gone) -- the caller then aborts and kills the child. */
static int write_frame(int fd, unsigned char type, const void *payload, uint32_t len) {
    unsigned char hdr[5];
    hdr[0] = type;
    hdr[1] = (unsigned char)(len >> 24); hdr[2] = (unsigned char)(len >> 16);
    hdr[3] = (unsigned char)(len >> 8);  hdr[4] = (unsigned char)(len);
    if (write_all(fd, hdr, 5) != 0) return -1;
    if (len && payload && write_all(fd, payload, len) != 0) return -1;
    return 0;
}

static int write_exit_frame(int fd, int32_t rc) {
    unsigned char b[4];
    uint32_t u = (uint32_t)rc;
    b[0] = (unsigned char)(u >> 24); b[1] = (unsigned char)(u >> 16);
    b[2] = (unsigned char)(u >> 8);  b[3] = (unsigned char)(u);
    return write_frame(fd, FRAME_EXIT, b, 4);
}

/* Read the whole command from the client (until half-close / EOF). If the command exceeds
 * MAX_CMD, sets *too_large=1 and returns NULL (the caller reports an error rather than running
 * a silently-truncated command). */
static char *read_command(int fd, size_t *out_len, int *too_large) {
    size_t cap = READ_CHUNK, len = 0;
    char *buf = (char *)malloc(cap);
    if (!buf) return NULL;
    for (;;) {
        if (len == cap) {
            if (cap >= MAX_CMD) { *too_large = 1; free(buf); return NULL; }
            cap *= 2; char *nb = (char *)realloc(buf, cap);
            if (!nb) { free(buf); return NULL; }
            buf = nb;
        }
        ssize_t n = read(fd, buf + len, cap - len);
        if (n < 0) { if (errno == EINTR) continue; free(buf); return NULL; }
        if (n == 0) break;
        len += (size_t)n;
    }
    *out_len = len;
    return buf;
}

/* Run cmd as root, streaming stdout/stderr frames to cfd as they arrive. Returns the
 * command exit code (or -1 on spawn failure). Continuously polls both pipes so the child
 * never blocks on a full pipe; aborts + kills the child if the client socket dies. */
static int run_streaming(int cfd, const char *cmd) {
    int out_pipe[2], err_pipe[2];
    if (pipe(out_pipe) != 0) return -1;
    if (pipe(err_pipe) != 0) { close(out_pipe[0]); close(out_pipe[1]); return -1; }

    pid_t pid = fork();
    if (pid < 0) {
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);
        return -1;
    }
    if (pid == 0) {
        /* child: become its own process-group leader so we can later kill the WHOLE tree
         * (the command + any backgrounded grandchildren) with kill(-pgid). Without this a
         * `sleep 100 &` style grandchild survives as root after the client disconnects. */
        setpgid(0, 0);
        /* become fully root, wire pipes to stdout/stderr, exec the shell. */
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);
        setgroups(0, NULL); setresgid(0, 0, 0); setresuid(0, 0, 0);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    /* Parent also sets the child's pgid (race-free: whichever runs first wins, both set the
     * same value). The pgid equals the child pid. */
    setpgid(pid, pid);
    close(out_pipe[1]); close(err_pipe[1]);

    /* Non-blocking reads so a burst on one pipe can't starve the other. */
    fcntl(out_pipe[0], F_SETFL, O_NONBLOCK);
    fcntl(err_pipe[0], F_SETFL, O_NONBLOCK);

    char buf[READ_CHUNK];
    int out_open = 1, err_open = 1;
    int client_alive = 1;
    while (out_open || err_open) {
        struct pollfd pfds[2];
        int nf = 0;
        int out_idx = -1, err_idx = -1;
        if (out_open) { pfds[nf].fd = out_pipe[0]; pfds[nf].events = POLLIN; out_idx = nf; nf++; }
        if (err_open) { pfds[nf].fd = err_pipe[0]; pfds[nf].events = POLLIN; err_idx = nf; nf++; }
        int pr = poll(pfds, nf, -1);
        if (pr < 0) { if (errno == EINTR) continue; break; }

        if (out_idx >= 0 && (pfds[out_idx].revents & (POLLIN | POLLHUP))) {
            for (;;) {
                ssize_t n = read(out_pipe[0], buf, sizeof(buf));
                if (n > 0) {
                    if (write_frame(cfd, FRAME_STDOUT, buf, (uint32_t)n) != 0) { client_alive = 0; goto drained; }
                } else if (n == 0) { out_open = 0; break; }
                else { if (errno == EAGAIN || errno == EWOULDBLOCK) break; if (errno == EINTR) continue; out_open = 0; break; }
            }
        }
        if (err_idx >= 0 && (pfds[err_idx].revents & (POLLIN | POLLHUP))) {
            for (;;) {
                ssize_t n = read(err_pipe[0], buf, sizeof(buf));
                if (n > 0) {
                    if (write_frame(cfd, FRAME_STDERR, buf, (uint32_t)n) != 0) { client_alive = 0; goto drained; }
                } else if (n == 0) { err_open = 0; break; }
                else { if (errno == EAGAIN || errno == EWOULDBLOCK) break; if (errno == EINTR) continue; err_open = 0; break; }
            }
        }
    }

drained:
    close(out_pipe[0]); close(err_pipe[0]);
    if (!client_alive) {
        /* Client vanished mid-command: kill the whole process group so backgrounded
         * grandchildren don't survive as root with no one watching. Negative pid = pgid.
         * On NORMAL completion we deliberately do NOT sweep the group, so a command that
         * intentionally backgrounds a process (`nohup daemon &`) behaves like a real shell
         * where such children survive `sh -c` exiting and get reparented to init. */
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

static void handle_client(int cfd) {
    /* Bound socket writes so a client that stops reading can't hang this handler (and its
     * root sh child via pipe back-pressure) forever. On timeout write_frame returns -1 and
     * run_streaming aborts + kills the command group. 120s is generous for a transient stall. */
    struct timeval sndto; sndto.tv_sec = 120; sndto.tv_usec = 0;
    setsockopt(cfd, SOL_SOCKET, SO_SNDTIMEO, &sndto, sizeof(sndto));

    size_t clen = 0;
    int too_large = 0;
    char *cmd = read_command(cfd, &clen, &too_large);
    if (too_large) { write_frame(cfd, FRAME_ERROR, "command too large", 17); close(cfd); return; }
    if (!cmd) { close(cfd); return; }
    char *cmdz = (char *)malloc(clen + 1);
    if (!cmdz) { free(cmd); write_frame(cfd, FRAME_ERROR, "oom", 3); close(cfd); return; }
    memcpy(cmdz, cmd, clen); cmdz[clen] = '\0';
    free(cmd);

    int rc = run_streaming(cfd, cmdz);
    free(cmdz);
    /* Always try to send the exit frame (harmless if the client already left). */
    write_exit_frame(cfd, rc);
    close(cfd);
}

/* Live handler-process count, maintained by reaping in a SIGCHLD handler so we can cap how many
 * concurrent root commands run at once (defense against a buggy/abusive client fanning out forks). */
#define MAX_HANDLERS 16
static volatile sig_atomic_t g_live_handlers = 0;

static void on_sigchld(int sig) {
    (void)sig;
    int saved = errno;
    pid_t p;
    while ((p = waitpid(-1, NULL, WNOHANG)) > 0) {
        if (g_live_handlers > 0) g_live_handlers--;
    }
    errno = saved;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    /* Reap handler forks via WNOHANG in the handler so we keep an accurate live count for the
     * concurrency cap. SA_RESTART so accept() isn't perturbed by the signal. */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_sigchld;
    sa.sa_flags = SA_RESTART | SA_NOCLDSTOP;
    sigaction(SIGCHLD, &sa, NULL);

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) { perror("appsud: socket"); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof(SOCK_NAME) - 1);
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(SOCK_NAME) - 1);

    if (bind(sfd, (struct sockaddr *)&addr, alen) != 0) { perror("appsud: bind"); return 1; }
    if (listen(sfd, 16) != 0) { perror("appsud: listen"); return 1; }

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) {
            /* Transient per-connection errors must not kill the root daemon. Back off briefly
             * on fd exhaustion; only a truly unrecoverable error breaks the loop. */
            if (errno == EINTR || errno == ECONNABORTED) continue;
            if (errno == EMFILE || errno == ENFILE) { usleep(100000); continue; }
            perror("appsud: accept");
            break;
        }
        /* Concurrency cap: refuse new connections past MAX_HANDLERS live handlers so a runaway
         * client can't spawn unbounded root processes. Send an ERROR frame so the caller knows. */
        if (g_live_handlers >= MAX_HANDLERS) {
            write_frame(cfd, FRAME_ERROR, "appsud busy (too many concurrent commands)", 42);
            close(cfd);
            continue;
        }
        /* Fork one handler per connection so a long command doesn't block new ones. */
        pid_t h = fork();
        if (h == 0) {
            close(sfd);
            /* Reset SIGCHLD so run_streaming's waitpid() for the sh child is deterministic. */
            signal(SIGCHLD, SIG_DFL);
            handle_client(cfd);
            _exit(0);
        }
        if (h < 0) {
            /* Fork failed: run inline as a fallback so the command still executes. */
            signal(SIGCHLD, SIG_DFL);
            handle_client(cfd);
            sigaction(SIGCHLD, &sa, NULL);
        } else {
            g_live_handlers++;
            /* Parent: the handler child owns cfd now. */
            close(cfd);
        }
    }
    close(sfd);
    return 0;
}
