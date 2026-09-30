#!/bin/bash
# TermFold: groupadd for a system whose storage cannot make hard links.
#
# The real groupadd locks /etc/group by hard-linking a file, and refuses to go on when the link
# is not a real one. This does the part packages need (add a group) directly.
system=0 gid="" force=0
while [ $# -gt 1 ]; do
    case "$1" in
        -r|--system) system=1 ;;
        -f|--force) force=1 ;;
        -g|--gid) gid=$2; shift ;;
        -K|--key|-p|--password|-R|--root) shift ;;
        --) shift; break ;;
        -*) ;;
        *) break ;;
    esac
    shift
done
name=${!#}
[ -n "$name" ] || { echo "Usage: groupadd [options] GROUP" >&2; exit 2; }
if grep -q "^$name:" /etc/group; then
    [ "$force" = 1 ] && exit 0
    echo "groupadd: group '$name' already exists" >&2
    exit 9
fi
taken() { cut -d: -f3 /etc/group | grep -qx "$1"; }
if [ -z "$gid" ]; then
    if [ "$system" = 1 ]; then
        gid=999
        while taken "$gid"; do gid=$((gid - 1)); done
    else
        gid=1000
        while taken "$gid"; do gid=$((gid + 1)); done
    fi
fi
echo "$name:x:$gid:" >> /etc/group
[ -f /etc/gshadow ] && echo "$name:!::" >> /etc/gshadow
exit 0
