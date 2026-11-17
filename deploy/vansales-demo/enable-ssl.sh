#!/bin/sh
# Run on the VPS once vansales.prolabtech.net resolves to this server (187.127.92.161).
# certbot certonly only: it must not edit nginx (:443 belongs to the stream SNI router).
set -e
DOMAIN=vansales.prolabtech.net
NS=$(dig +short NS prolabtech.net | head -1)
IP=$(dig +short "$DOMAIN" @"$NS" | tail -1)
[ "$IP" = "187.127.92.161" ] || { echo "DNS for $DOMAIN points to '$IP', not 187.127.92.161 - add the A record first"; exit 1; }
certbot certonly --nginx -d "$DOMAIN" --non-interactive --agree-tos -m sales@prolabtech.net
cp /root/vansales-odoo/nginx/vansales.ssl.conf /etc/nginx/conf.d/vansales.conf
nginx -t && systemctl reload nginx
curl -s -o /dev/null -w "https://$DOMAIN/web/login -> %{http_code}\n" "https://$DOMAIN/web/login"
curl -s "https://$DOMAIN/sales_rep/api/v1/ping"; echo
