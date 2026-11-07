#!/usr/bin/env bash
# IPTV Pro Max — установка веб-версии на Ubuntu/Debian сервер.
# Запуск:  curl -fsSL https://raw.githubusercontent.com/Arthuralend/Hald-IPTV/main/web/install.sh | sudo bash
set -e
DIR=/opt/iptv-pro-max
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
apt-get install -y git >/dev/null 2>&1 || true
if [ -d "$DIR/.git" ]; then git -C "$DIR" pull -q; else git clone -q --depth 1 https://github.com/Arthuralend/Hald-IPTV "$DIR"; fi
cd "$DIR/web"
mkdir -p cache && chown 1000:1000 cache
if [ ! -f .env ]; then
  cp .env.example .env
  sed -i "s|^SECRET=.*|SECRET=$(openssl rand -hex 32)|" .env
  if [ -t 0 ] || [ -e /dev/tty ]; then
    read -r -p "Ссылка на плейлист: " URL </dev/tty
    read -r -p "Пароль на сайт (Enter — без пароля): " PW </dev/tty
    sed -i "s|^PRESET_URL=.*|PRESET_URL=$URL|" .env
    sed -i "s|^SITE_PASSWORD=.*|SITE_PASSWORD=$PW|" .env
  fi
  chmod 600 .env
fi
docker compose up -d --build
PORT=$(grep ^HOST_PORT .env | cut -d= -f2); PORT=${PORT:-8090}
IP=$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')
echo
echo "Готово: http://$IP:$PORT"
echo "Настройки: $DIR/web/.env  (после изменения: cd $DIR/web && docker compose up -d)"
