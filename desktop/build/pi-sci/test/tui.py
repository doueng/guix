import errno
import fcntl
import os
import pty
import select
import signal
import struct
import sys
import termios
import time

pid, fd = pty.fork()
if pid == 0:
    os.environ["TERM"] = "xterm-256color"
    os.execvp(sys.argv[1], sys.argv[1:])

fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 32, 100, 0, 0))
chunks = []
deadline = time.monotonic() + 25
finished = False
status = None
try:
    while time.monotonic() < deadline:
        ready, _, _ = select.select([fd], [], [], 0.1)
        if ready:
            try:
                data = os.read(fd, 65536)
            except OSError as error:
                if error.errno != errno.EIO:
                    raise
                break
            if not data:
                break
            chunks.append(data)
            output = b"".join(chunks)
            if len(output) > 2 * 1024 * 1024:
                raise RuntimeError("TUI output exceeded the test limit")
            if not finished and b"fixture complete" in output:
                finished = True
                os.write(fd, b"\x04")
        done, status = os.waitpid(pid, os.WNOHANG)
        if done:
            break
    else:
        raise RuntimeError("Pi TUI did not finish before the deadline")
finally:
    if status is None:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        _, status = os.waitpid(pid, 0)
    os.close(fd)

sys.stdout.buffer.write(b"".join(chunks))
if not finished:
    raise RuntimeError("Pi TUI did not render the completed fixture")
