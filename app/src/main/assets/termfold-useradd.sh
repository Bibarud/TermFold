#!/bin/bash
# TermFold: useradd for a system whose storage cannot make hard links (see termfold-groupadd.sh).
system=0 uid="" gid="" home="" shell="/bin/sh" comment="" groups="" mkhome=0 nogroup=0
while [ $# -gt 1 ]; do
    case "$1" in
        -r|--system) system=1 ;;
        -u|--uid) uid=$2; shift ;;
        -g|--gid) gid=$2; shift ;;
        -d|--home-dir|--home) home=$2; shift ;;
        -s|--shell) shell=$2; shift ;;
        -c|--comment) comment=$2; shift ;;
        -G|--groups) groups=$2; shift ;;
        -m|--create-home) mkhome=1 ;;
        -N|--no-user-group) nogroup=1 ;;
        -b|-e|-f|-k|-K|-p|-R|-P|-Z|--base-dir|--expiredate|--inactive|--skel|--key|--password|--root|--prefix|--selinux-user) shift ;;
        --) shift; break ;;
        -*) ;;
        *) break ;;
    esac
    shift
done
name=${!#}
[ -n "$name" ] || { echo "Usage: useradd [options] LOGIN" >&2; exit 2; }
if grep -q "^$name:" /etc/passwd; then
    echo "useradd: user '$name' already exists" >&2
    exit 9
fi
taken_uid() { cut -d: -f3 /etc/passwd | grep -qx "$1"; }
taken_gid() { cut -d: -f3 /etc/group | grep -qx "$1"; }
if [ -z "$uid" ]; then
    if [ "$system" = 1 ]; then
        uid=999
        while taken_uid "$uid"; do uid=$((uid - 1)); done
    else
        uid=1000
        while taken_uid "$uid"; do uid=$((uid + 1)); done
    fi
fi
# The primary group: the one given (a name or a number), the user's own new group, or "users".
if [ -n "$gid" ]; then
    case "$gid" in
        *[!0-9]*) found=$(grep "^$gid:" /etc/group | cut -d: -f3); [ -n "$found" ] || { echo "useradd: group '$gid' does not exist" >&2; exit 6; }; gid=$found ;;
    esac
elif [ "$nogroup" = 1 ]; then
    gid=100
else
    if grep -q "^$name:" /etc/group; then
        gid=$(grep "^$name:" /etc/group | cut -d: -f3)
    else
        gid=$uid
        while taken_gid "$gid"; do gid=$((gid - 1)); done
        echo "$name:x:$gid:" >> /etc/group
        [ -f /etc/gshadow ] && echo "$name:!::" >> /etc/gshadow
    fi
fi
[ -n "$home" ] || home="/home/$name"
echo "$name:x:$uid:$gid:$comment:$home:$shell" >> /etc/passwd
[ -f /etc/shadow ] && echo "$name:!:19000:0:99999:7:::" >> /etc/shadow
# Supplementary groups: the user is added to each group's member list.
for g in ${groups//,/ }; do
    grep -q "^$g:" /etc/group || continue
    line=$(grep "^$g:" /etc/group)
    members=${line##*:}
    if [ -z "$members" ]; then new="$name"; else new="$members,$name"; fi
    sed -i "s/^\($g:[^:]*:[^:]*:\).*\$/\1$new/" /etc/group
done
if [ "$mkhome" = 1 ]; then
    mkdir -p "$home" && chown "$uid:$gid" "$home" 2>/dev/null
    chmod 755 "$home"
fi
exit 0
