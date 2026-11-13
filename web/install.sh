#!/usr/bin/env bash
# IPTV Pro Max — установка веб-версии на Ubuntu/Debian сервер.
# Запуск:  curl -fsSL https://raw.githubusercontent.com/Arthuralend/TV/main/web/install.sh | sudo bash
set -e
DIR=/opt/iptv-pro-max
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
apt-get install -y git >/dev/null 2>&1 || true
if [ -d "$DIR/.git" ]; then git -C "$DIR" pull -q; else git clone -q --depth 1 https://github.com/Arthuralend/TV "$DIR"; fi
cd "$DIR/web"
mkdir -p cache && chown 1000:1000 cache
if [ ! -f .env ]; then
  cp .env.example .env
  sed -i "s|^SECRET=.*|SECRET=$(openssl rand -hex 32)|" .env
  if [ -t 0 ] || [ -e /dev/tty ]; then
    read -r -p "Ссылка на плейлист или путь к файлу на сервере: " URL </dev/tty
    if [ -f "$URL" ]; then
      cp "$URL" cache/source.m3u8 && chown 1000:1000 cache/source.m3u8
      URL=/app/cache/source.m3u8
    fi
    read -r -p "Пароль на сайт (Enter — без пароля): " PW </dev/tty
    sed -i "s|^PRESET_URL=.*|PRESET_URL=$URL|" .env
    sed -i "s|^SITE_PASSWORD=.*|SITE_PASSWORD=$PW|" .env
  fi
  chmod 600 .env
fi
# a playlist file uploaded next to the installer replaces the source:  sudo bash install.sh /path/to/playlist.m3u8
if [ -n "$1" ] && [ -f "$1" ]; then
  cp "$1" cache/source.m3u8 && chown 1000:1000 cache/source.m3u8
  sed -i "s|^PRESET_URL=.*|PRESET_URL=/app/cache/source.m3u8|" .env
fi
docker compose up -d --build --force-recreate
PORT=$(grep ^HOST_PORT .env | cut -d= -f2); PORT=${PORT:-8090}
IP=$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')
echo
echo "Готово: http://$IP:$PORT"
echo "Настройки: $DIR/web/.env  (после изменения: cd $DIR/web && docker compose up -d)"
