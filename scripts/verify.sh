#!/usr/bin/env bash
# Ad-hoc verification for demoEJB_Transaction (no JUnit/Arquillian suite exists).
# Rebuilds, hot-deploys, then asserts: classic form POST, the client-side fetch path,
# that the servlet was NOT changed, and the JTA commit/rollback behaviour against MySQL.
# Usage: bash scripts/verify.sh
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -f "$HERE/../pom.xml" ]; then
  PROJ=$(cd "$HERE/.." && pwd)                       # checked in at <project>/scripts/verify.sh
else
  PROJ=${PROJECT_DIR:-/d/APPS_EJB_20261119/demoEJB_Transaction}   # run from elsewhere (e.g. %TEMP%)
fi
BASE=http://localhost:8080/demoEJB-Transaction
MYSQL=${MYSQL_CLIENT:-"/d/Program Files/MySQL/MySQL Workbench 8.0 CE/mysql.exe"}
MVN=${MVN:-/d/APPS_EJB_20261119/tools/mvn.sh}
export MYSQL_PWD=${MYSQL_PWD:-OceanPark88}
T=$(mktemp -d -t hermes-verify-XXXXXX); TW=$(cygpath -w "$T")
P=0; F=0
ok(){ echo "  PASS  $1"; P=$((P+1)); }
no(){ echo "  FAIL  $1  <<< $2"; F=$((F+1)); }
chk(){ [ "$2" = "$3" ] && ok "$1 ($2)" || no "$1" "want [$3] got [$2]"; }
q(){ "$MYSQL" -N -B -h 127.0.0.1 -P 3308 -u ejb_demo_user -D demoEJB_DB -e "$1" 2>/dev/null | tr -d '\r'; }

cd "$PROJ" || exit 1
echo "== 1. build =="
"$MVN" -B -ntp clean package 2>&1 | grep -q "BUILD SUCCESS" && ok "mvn clean package" || { no "mvn build" failed; rm -rf "$T"; exit 1; }
L=$(unzip -l target/demoEJB-Transaction.war | awk '{print $4}')
echo "$L" | grep -qx "WEB-INF/classes/com/example/transaction/RegisterServlet.class" && ok "servlet in WAR" || no "servlet in WAR" absent
echo "$L" | grep -qx "index.html" && ok "index.html in WAR" || no "index.html in WAR" absent

echo "== 2. deploy =="
MSYS_NO_PATHCONV=1 cmd.exe /c "scripts\\deploy.bat" 2>&1 | grep -q "HTTP 200" && ok "deploy.bat -> HTTP 200" || no "deploy" failed

# Hot redeploy unregisters/re-registers the web context (WFLYUT0022 -> WFLYUT0021);
# requests in that ~1s gap get 200 with an EMPTY body. Require stable content before asserting.
READY=0
for i in $(seq 1 40); do
  g=0
  for k in 1 2 3; do
    curl -s "$BASE/" | grep -q 'id="btnJs"' && g=$((g+1)) || break
    sleep 0.4
  done
  [ "$g" -eq 3 ] && { READY=1; break; }
  sleep 1
done
chk "content stable after deploy (not the 200+empty gap)" "$READY" "1"

echo "== 3. page: two fields, two buttons, client-side parsing =="
curl -s -o "$TW\\p.html" "$BASE/"
chk "input fields" "$(grep -c 'name="custName"\|name="tel"' "$T/p.html")" "2"
chk "Submit button" "$(grep -c 'id="btnForm"' "$T/p.html")" "1"
chk "Submit-JS button" "$(grep -c 'id="btnJs"' "$T/p.html")" "1"
grep -q "fetch('register'" "$T/p.html" && ok "fetch('register') wired" || no "fetch call" missing
grep -q "DOMParser" "$T/p.html" && ok "parses the HTML response (DOMParser)" || no "DOMParser" missing
grep -q "fetch('register?format=json'" "$T/p.html" && no "fetch URL must not use ?format=json" "found in fetch()" || ok "fetch URL does not rely on ?format=json"

echo "== 4. servlet unchanged: no JSON mode =="
curl -s -D "$TW\\h0" -o /dev/null -X POST -d "custName=Alice&tel=1" "$BASE/register?format=json"
grep -qi "content-type: text/html" "$T/h0" && ok "?format=json still returns text/html" || no "JSON mode leaked back" "$(grep -i content-type "$T/h0")"

echo "== 5. classic path: form POST -> full HTML page =="
curl -s -D "$TW\\h1" -o "$TW\\b1" -X POST -d "custName=Alice&tel=1" "$BASE/register"
grep -qi "content-type: text/html" "$T/h1" && ok "text/html" || no "content-type" "$(grep -i content-type "$T/h1")"
grep -q "<table" "$T/b1" && ok "server-rendered table" || no "table" missing

echo "== 6. fetch path: HTML the JS parses =="
curl -s -o "$TW\\b2" -X POST -d "custName=JsOnly&tel=2766 7777" "$BASE/register"
grep -q 'class="msg ok"' "$T/b2" && ok "response has .msg ok (what JS reads)" || no "msg ok" "not found"
grep -q "<td>JsOnly</td>" "$T/b2" && ok "response table contains the new row" || no "new row in HTML" "missing"
chk "row committed to MySQL" "$(q "SELECT COUNT(*) FROM tblCustomer WHERE Cust_Name='JsOnly'")" "1"

echo "== 7. rollback paths =="
curl -s -o "$TW\\b3" -X POST -d "custName=Alice&tel=999" "$BASE/register"
grep -q 'class="msg bad"' "$T/b3" && ok "duplicate -> .msg bad" || no "duplicate msg" "not bad"
grep -q "ROLLED BACK" "$T/b3" && ok "duplicate -> ROLLED BACK text" || no "duplicate text" "missing"
chk "duplicate -> tel untouched" "$(q "SELECT Tel FROM tblCustomer WHERE Cust_Name='Alice'")" "12345678"
curl -s -o "$TW\\b4" -X POST -d "custName=JsOnlyFail&tel=1&forceFail=true" "$BASE/register"
grep -q "ROLLED BACK" "$T/b4" && ok "forced fail -> ROLLED BACK" || no "forced fail text" "missing"
chk "forced fail -> no customer row" "$(q "SELECT COUNT(*) FROM tblCustomer WHERE Cust_Name='JsOnlyFail'")" "0"
chk "forced fail -> no orphan account row" "$(q "SELECT COUNT(*) FROM tblAccount WHERE Cust_Name='JsOnlyFail'")" "0"

echo "== 8. documented trap: JSON body unreadable by getParameter =="
curl -s -o "$TW\\b5" -X POST -H "Content-Type: application/json" -d '{"custName":"JsOnlyJson","tel":"1"}' "$BASE/register"
grep -q "Both fields are required" "$T/b5" && ok "JSON body -> 'Both fields are required.'" || no "JSON body" "unexpected response"
chk "JSON body -> nothing inserted" "$(q "SELECT COUNT(*) FROM tblCustomer WHERE Cust_Name='JsOnlyJson'")" "0"

echo "== 9. cleanup test rows =="
q "DELETE FROM tblAccount WHERE Cust_Name IN ('JsOnly','JsOnlyFail','JsOnlyJson')" >/dev/null
q "DELETE FROM tblCustomer WHERE Cust_Name IN ('JsOnly','JsOnlyFail','JsOnlyJson')" >/dev/null
echo "  DB: $(q 'SELECT GROUP_CONCAT(Cust_Name ORDER BY Cust_Name) FROM tblCustomer') / $(q 'SELECT COUNT(*) FROM tblAccount') accounts"
rm -rf "$T"
echo; echo "RESULT: $P passed, $F failed"; [ "$F" -eq 0 ] || exit 1
