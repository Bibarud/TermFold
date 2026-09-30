#!/bin/bash
# TermFold first-run setup: turns the bare Debian base image into a coding environment.
#
# The base image ships without package lists, a compiler, git, Python tooling or Node, so agent
# CLIs installed straight after the app cannot run. This brings the system up to date and puts
# those in place. It runs automatically in the first Shell session (see ShellSetup.kt) and can be
# re-run at any time with `termfold-setup`; every step is safe to repeat.
#
# @NODE_VERSION@ is filled in by the app, so the Node here is the same one its agent installer
# expects and neither replaces the other's.

set -u
NODE_VERSION="@NODE_VERSION@"
STATE=/var/lib/termfold
MARK="$STATE/setup-v1"

mkdir -p "$STATE"

# Two sessions opened at once must not run apt side by side; the second waits for the first.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$STATE/setup.lock"
  flock 9
fi
if [ -f "$MARK" ] && [ "${1:-}" != "--force" ]; then
  exit 0
fi

orange=$'\033[38;5;208m'; dim=$'\033[2m'; red=$'\033[31m'; green=$'\033[32m'; reset=$'\033[0m'
step() { printf '\n%s==>%s %s\n' "$orange" "$reset" "$*"; }
fail() {
  printf '\n%s%s%s\n' "$red" "$*" "$reset"
  printf '%sSetup will run again the next time you open a Shell, or run: termfold-setup%s\n\n' "$dim" "$reset"
  exit 1
}

printf '\n%sSetting up Debian for coding%s\n' "$orange" "$reset"
printf '%sOne time only. This downloads a few hundred MB and takes several minutes; keep the app open.%s\n' "$dim" "$reset"

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

# Android refuses hard links in app storage, and groupadd/useradd (which packages such as
# openssh-client run when they are installed) lock /etc/group with one, so they fail and leave the
# package half installed. Swap in TermFold's versions first; dpkg keeps the diversion, so package
# updates leave it alone.
for tool in groupadd useradd; do
  [ -x "/usr/local/lib/termfold/shadow-lite/$tool" ] || continue
  if [ ! -e "/usr/sbin/$tool.real" ]; then
    dpkg-divert --local --rename --divert "/usr/sbin/$tool.real" --add "/usr/sbin/$tool" >/dev/null 2>&1 || continue
  fi
  ln -sf "/usr/local/lib/termfold/shadow-lite/$tool" "/usr/sbin/$tool"
done

step "Updating package lists"
apt-get update || fail "Could not reach the Debian archive. Check the connection."

# An install that was cut off (the app was closed, the battery died) leaves packages half
# configured, and apt refuses to do anything else until they are finished.
if dpkg --audit 2>/dev/null | grep -q .; then
  step "Finishing an interrupted install"
  dpkg --configure -a >/dev/null 2>&1
  apt-get "${APT_OPTS[@]}" -f install >/dev/null 2>&1
fi

step "Upgrading installed packages"
apt-get "${APT_OPTS[@]}" full-upgrade || fail "Upgrading packages failed."

step "Installing developer tools"
apt-get "${APT_OPTS[@]}" install --no-install-recommends \
  ca-certificates curl wget git openssh-client gnupg \
  build-essential pkg-config \
  python3 python3-pip python3-venv python-is-python3 \
  less nano vim-tiny unzip zip xz-utils bzip2 file procps psmisc \
  jq ripgrep fd-find tree tzdata \
  || fail "Installing developer tools failed."

step "Installing Node.js $NODE_VERSION"
if [ "$(cat /opt/node/.termfold-version 2>/dev/null)" != "$NODE_VERSION" ]; then
  case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) fail "No Node.js build for $(uname -m)." ;;
  esac
  url="https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-$arch.tar.xz"
  curl -fL --retry 3 -o /tmp/node.tar.xz "$url" || fail "Downloading Node.js failed."
  rm -rf /opt/node.new && mkdir -p /opt/node.new
  tar -xJf /tmp/node.tar.xz -C /opt/node.new --strip-components=1 || fail "Unpacking Node.js failed."
  rm -rf /opt/node && mv /opt/node.new /opt/node && rm -f /tmp/node.tar.xz
  printf '%s' "$NODE_VERSION" > /opt/node/.termfold-version
else
  printf '%salready installed%s\n' "$dim" "$reset"
fi
export PATH="/opt/node/bin:$PATH"
npm config set fund false >/dev/null 2>&1
npm config set update-notifier false >/dev/null 2>&1

# fd-find installs as `fdfind` on Debian; agents and people both expect `fd`.
[ -e /usr/local/bin/fd ] || ln -s "$(command -v fdfind)" /usr/local/bin/fd 2>/dev/null

# After a move from Ubuntu (see DistroMigration.kt) the apt packages the user had asked for are
# listed here. They are not installed automatically: the list can include desktop apps of
# hundreds of MB. Say what there is and how to install it.
LIST=/var/lib/termfold/migrated-packages
if [ -s "$LIST" ]; then
  mkdir -p /root/.termfold
  cp -f "$LIST" /root/.termfold/ubuntu-packages.txt
  printf '\n%sYou had %s apt packages installed on Ubuntu.%s\n' "$orange" "$(grep -c . "$LIST")" "$reset"
  printf '%sThe list is in ~/.termfold/ubuntu-packages.txt. To install the ones you still want:%s\n' "$dim" "$reset"
  printf '  apt install <names>          %s(or everything: xargs -a ~/.termfold/ubuntu-packages.txt apt-get install -y)%s\n' "$dim" "$reset"
  mv -f "$LIST" "$LIST.done"
fi

touch "$MARK"
printf '\n%sReady.%s node %s · npm %s · python %s · git %s\n' "$green" "$reset" \
  "$(node --version)" "$(npm --version)" "$(python3 --version | cut -d' ' -f2)" "$(git --version | cut -d' ' -f3)"
printf '%sAgent CLIs install themselves the first time you run them: claude, codex, gemini, opencode, pi, qwen.%s\n\n' "$dim" "$reset"
