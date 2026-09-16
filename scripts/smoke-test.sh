#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Prueba de humo end-to-end contra el BFF en perfil dev.
# Verifica, en este orden:
#   1. Un endpoint protegido sin token responde 401.
#   2. Se puede obtener un token de demo por rol.
#   3. El token relay funciona (BFF -> orders -> catalog).
#   4. La maquina de estados bloquea "despachar sin aceptar" (409).
#   5. La autorizacion por rol bloquea al Cliente (403).
#   6. El stock decrece al aceptar el pedido.
# ---------------------------------------------------------------------------
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"

token() { curl -s "$BASE/dev/token?username=$1&roles=$2" | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])"; }
jqp()   { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "1) Sin token"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/orders")
[ "$code" = "401" ] && echo "   OK 401" || { echo "   FALLO: se esperaba 401, llego $code"; exit 1; }

ADMIN=$(token "admin@pedidos360.cl" ADMIN)
OP=$(token "operador@pedidos360.cl" OPERATOR)
CUST=$(token "cliente@pedidos360.cl" CUSTOMER)
echo "2) Tokens emitidos"

echo "3) Catalogo via BFF"
stock_before=$(curl -s "$BASE/api/catalog/products" -H "Authorization: Bearer $ADMIN" \
  | python3 -c "import sys,json;print([p['stock'] for p in json.load(sys.stdin) if p['sku']=='SKU-001'][0])")
echo "   stock SKU-001 = $stock_before"

echo "4) Crear pedido como Cliente"
OID=$(curl -s -X POST "$BASE/api/orders" -H "Authorization: Bearer $CUST" -H "Content-Type: application/json" \
  -d '{"deliveryAddress":"Calle Falsa 123","items":[{"sku":"SKU-001","quantity":2}]}' | jqp "['id']")
echo "   pedido $OID"

echo "5) Despachar sin aceptar (debe ser 409)"
code=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE/api/orders/$OID/status" \
  -H "Authorization: Bearer $OP" -H "Content-Type: application/json" -d '{"status":"DISPATCHED"}')
[ "$code" = "409" ] && echo "   OK 409" || { echo "   FALLO: se esperaba 409, llego $code"; exit 1; }

echo "6) Cliente intentando aceptar (debe ser 403)"
code=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE/api/orders/$OID/status" \
  -H "Authorization: Bearer $CUST" -H "Content-Type: application/json" -d '{"status":"ACCEPTED"}')
[ "$code" = "403" ] && echo "   OK 403" || { echo "   FALLO: se esperaba 403, llego $code"; exit 1; }

echo "7) Operador acepta -> el stock debe bajar 2"
curl -s -X PATCH "$BASE/api/orders/$OID/status" -H "Authorization: Bearer $OP" \
  -H "Content-Type: application/json" -d '{"status":"ACCEPTED"}' > /dev/null
stock_after=$(curl -s "$BASE/api/catalog/products" -H "Authorization: Bearer $ADMIN" \
  | python3 -c "import sys,json;print([p['stock'] for p in json.load(sys.stdin) if p['sku']=='SKU-001'][0])")
echo "   stock SKU-001 = $stock_after"
[ "$stock_after" = "$((stock_before - 2))" ] && echo "   OK descuento de stock" || { echo "   FALLO en el descuento"; exit 1; }

echo "8) Cliente pidiendo reporteria (debe ser 403)"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/report/kpis" -H "Authorization: Bearer $CUST")
[ "$code" = "403" ] && echo "   OK 403" || echo "   AVISO: llego $code (revisa que ms-report este arriba)"

echo
echo "Prueba de humo completa."
