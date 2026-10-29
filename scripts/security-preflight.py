#!/usr/bin/env python3
"""兼容旧预检入口：只校验，不改写凭证或启动容器。"""
from deploy import main

if __name__ == "__main__":
    import sys
    sys.exit(main(["--check", *sys.argv[1:]]))
