#!/usr/bin/env python3
"""Bridge a REAL Linux tun device to the Fast Wi-Fi engine under test (tools/test/FastE2E.java). Not part of the app.

Makes the tun device "lmfast0" with the tablet VPN's address (10.111.222.2/24) and MTU (10000), routes the test "internet"
198.18.0.0/15 into it, then connects to the Java harness on 127.0.0.1:<port> and copies whole packets both ways, each with a
2-byte length in front. So the Linux kernel's own TCP/UDP (Java sockets in the harness talking to 198.18.0.1) is the "app", exactly
as Android's kernel is on the tablet. Needs root and /dev/net/tun. Usage: fast_tun_bridge.py <port>
"""
import ctypes
import fcntl
import os
import socket
import struct
import sys
import threading

NAME = b"lmfast0"
TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000
SIOCSIFADDR = 0x8916
SIOCSIFNETMASK = 0x891C
SIOCSIFMTU = 0x8922
SIOCGIFFLAGS = 0x8913
SIOCSIFFLAGS = 0x8914
SIOCADDRT = 0x890B
RTF_UP = 0x0001


class SockaddrIn(ctypes.Structure):
    _fields_ = [("family", ctypes.c_ushort), ("port", ctypes.c_ushort), ("addr", ctypes.c_ubyte * 4), ("zero", ctypes.c_ubyte * 8)]


class RtEntry(ctypes.Structure):
    _fields_ = [
        ("rt_pad1", ctypes.c_ulong),
        ("rt_dst", SockaddrIn),
        ("rt_gateway", SockaddrIn),
        ("rt_genmask", SockaddrIn),
        ("rt_flags", ctypes.c_ushort),
        ("rt_pad2", ctypes.c_short),
        ("rt_pad3", ctypes.c_ulong),
        ("rt_pad4", ctypes.c_void_p),
        ("rt_metric", ctypes.c_short),
        ("rt_dev", ctypes.c_char_p),
        ("rt_mtu", ctypes.c_ulong),
        ("rt_window", ctypes.c_ulong),
        ("rt_irtt", ctypes.c_ushort),
    ]


def sin(addr):
    s = SockaddrIn()
    s.family = socket.AF_INET
    for i, b in enumerate(socket.inet_aton(addr)):
        s.addr[i] = b
    return s


def ifreq_addr(addr):
    return struct.pack("16sH2s4s8s", NAME, socket.AF_INET, b"\x00" * 2, socket.inet_aton(addr), b"\x00" * 8)


def main():
    port = int(sys.argv[1])
    fd = os.open("/dev/net/tun", os.O_RDWR)
    fcntl.ioctl(fd, TUNSETIFF, struct.pack("16sH", NAME, IFF_TUN | IFF_NO_PI))
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    fcntl.ioctl(s, SIOCSIFADDR, ifreq_addr("10.111.222.2"))
    fcntl.ioctl(s, SIOCSIFNETMASK, ifreq_addr("255.255.255.0"))
    fcntl.ioctl(s, SIOCSIFMTU, struct.pack("16si", NAME, 10000))
    flags = struct.unpack("16sH", fcntl.ioctl(s, SIOCGIFFLAGS, struct.pack("16sH", NAME, 0))[:18])[1]
    fcntl.ioctl(s, SIOCSIFFLAGS, struct.pack("16sH", NAME, flags | 0x1))
    rt = RtEntry()
    rt.rt_dst = sin("198.18.0.0")
    rt.rt_genmask = sin("255.254.0.0")
    rt.rt_flags = RTF_UP
    rt.rt_dev = ctypes.c_char_p(NAME)
    fcntl.ioctl(s, SIOCADDRT, rt)

    c = socket.create_connection(("127.0.0.1", port))
    c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    done = threading.Event()

    def tun_to_sock():
        try:
            while not done.is_set():
                pkt = os.read(fd, 65536)
                c.sendall(struct.pack(">H", len(pkt)) + pkt)
        except OSError:
            pass
        done.set()

    def recv_exact(n):
        buf = bytearray()
        while len(buf) < n:
            chunk = c.recv(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return bytes(buf)

    def sock_to_tun():
        try:
            while not done.is_set():
                (n,) = struct.unpack(">H", recv_exact(2))
                os.write(fd, recv_exact(n))
        except (OSError, EOFError):
            pass
        done.set()

    threading.Thread(target=tun_to_sock, daemon=True).start()
    threading.Thread(target=sock_to_tun, daemon=True).start()
    done.wait()
    os.close(fd)  # the device and its route go with the last descriptor


if __name__ == "__main__":
    main()
