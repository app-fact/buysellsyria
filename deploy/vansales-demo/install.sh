#!/bin/bash
# One-time setup of the van-sales demo on hostinger-vps. Run from /root/vansales-odoo after the
# files of this folder were copied there and the module was copied to ./addons/sales_rep_mobile.
# Never prints passwords: they go to CREDENTIALS.txt (mode 600).
#   ADMIN_LOGIN=... MANAGER_LOGIN=... ./install.sh
set -euo pipefail
cd "$(dirname "$0")"
PORT=${ODOO_PORT:-8188}
DB=vansales
ADMIN_LOGIN=${ADMIN_LOGIN:-admin.vansales}
MANAGER_LOGIN=${MANAGER_LOGIN:-manager.vansales}
APP=vansales-odoo
DBC=vansales-odoo-db

[ -d addons/sales_rep_mobile ] || { echo "addons/sales_rep_mobile missing"; exit 1; }
if docker ps -a --format '{{.Names}}' | grep -qx "$APP" ; then echo "$APP already exists - refusing to reinstall"; exit 1; fi
if ss -ltn | grep -q "127.0.0.1:$PORT " ; then echo "port $PORT is busy - set ODOO_PORT"; exit 1; fi
[ -e /etc/nginx/conf.d/vansales.conf ] && { echo "/etc/nginx/conf.d/vansales.conf exists"; exit 1; }

umask 077
mkdir -p config backups
if [ ! -f .env ]; then
  printf 'DB_PASSWORD=%s\nODOO_PORT=%s\n' "$(openssl rand -hex 24)" "$PORT" > .env
fi
set -a; . ./.env; set +a
if [ ! -f config/odoo.conf ]; then
  MASTER=$(openssl rand -hex 24)
  sed "s|<ODOO_MASTER_PASSWORD>|$MASTER|" odoo.conf.example > config/odoo.conf
  echo "odoo master password (database manager is disabled): $MASTER" > CREDENTIALS.txt
fi
chmod 644 config/odoo.conf   # read by the odoo user inside the container
sed -i "s|127.0.0.1:8188|127.0.0.1:$PORT|" nginx/vansales.conf nginx/vansales.ssl.conf

docker compose up -d $DBC
for i in $(seq 1 30); do docker compose exec -T $DBC pg_isready -U odoo -q && break; sleep 2; done

DBARGS=(--db_host $DBC --db_user odoo --db_password "$DB_PASSWORD" -c /etc/odoo/odoo.conf -d $DB)
echo "== creating database $DB (Arabic, Odoo 20, sales + stock + invoicing + van sales)"
docker compose run --rm -T --entrypoint odoo $APP "${DBARGS[@]}" \
  -i base,sale_management,stock,account,sales_rep_mobile --load-language ar_SY --stop-after-init --no-http

echo "== demo data"
ADMIN_PASSWORD=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)
MANAGER_PASSWORD=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)
R01_PIN=$(shuf -i 1000-9999 -n 1); R02_PIN=$(shuf -i 1000-9999 -n 1)
OUT=$(mktemp)
docker compose run --rm -T --entrypoint odoo \
  -e ADMIN_LOGIN="$ADMIN_LOGIN" -e ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -e MANAGER_LOGIN="$MANAGER_LOGIN" -e MANAGER_PASSWORD="$MANAGER_PASSWORD" \
  -e R01_PIN="$R01_PIN" -e R02_PIN="$R02_PIN" -e DRY_RUN="${DRY_RUN:-0}" \
  $APP shell "${DBARGS[@]}" --no-http < scripts/demo_data.py > "$OUT" 2>&1 || { grep -v '^CRED' "$OUT"; rm -f "$OUT"; exit 1; }
grep -v '^CRED' "$OUT"
{ echo "# vansales.prolabtech.net  $(date -u +%F)"; grep '^CRED' "$OUT" | sed 's/^CRED //'; } >> CREDENTIALS.txt
rm -f "$OUT"; chmod 600 CREDENTIALS.txt .env

docker compose up -d $APP
cp nginx/vansales.conf /etc/nginx/conf.d/vansales.conf
nginx -t && systemctl reload nginx

# daily backup 02:40, keep 14 days
cat > /etc/cron.d/vansales-backup <<CRON
40 2 * * * root cd /root/vansales-odoo && docker compose exec -T $DBC pg_dump -U odoo -Fc $DB > backups/${DB}_\$(date +\%Y\%m\%d).dump && find backups -name '*.dump' -mtime +14 -delete
CRON

sleep 5
curl -s -o /dev/null -w "local /web/login -> %{http_code}\n" "http://127.0.0.1:$PORT/web/login"
curl -s "http://127.0.0.1:$PORT/sales_rep/api/v1/ping"; echo
echo "Done. Credentials: /root/vansales-odoo/CREDENTIALS.txt  - next: DNS A record, then ./enable-ssl.sh"
