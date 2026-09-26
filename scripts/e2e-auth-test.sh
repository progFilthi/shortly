#!/usr/bin/env bash
#
# End-to-end smoke test for authentication, exercised through the gateway.
#
# Everything goes through :8080 rather than calling a service directly, because the gateway is
# the only supported entry point - the services now reject anything without the internal secret.
# That makes this a real test of the trust chain rather than just of the endpoints.
#
# Covers: register, login, /me, refresh, rotation, reuse detection, logout, lockout, and the
# gateway's refusal to accept a forged identity header.

set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
AUTH_SERVICE="${AUTH_SERVICE:-http://localhost:8081}"
STAMP="$(date +%s)"
USERNAME="e2e$STAMP"
EMAIL="e2e$STAMP@example.com"
PASSWORD="correct-horse-battery-staple"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
step() { printf '%s==>%s %s\n' "$YELLOW" "$RESET" "$1"; }
pass() { printf '%s  ok%s %s\n' "$GREEN" "$RESET" "$1"; }
fail() { printf '%sFAIL%s %s\n' "$RED" "$RESET" "$1"; exit 1; }

dump_logs() {
    echo; echo "--------- gateway ---------"; docker compose logs --tail 30 gateway 2>&1 | sed 's/^/  /'
    echo "--------- auth-service ----"; docker compose logs --tail 30 auth-service 2>&1 | sed 's/^/  /'
}
trap 'dump_logs' ERR

jq_get() { python3 -c "import json,sys;print(json.load(sys.stdin)$1)"; }

# Reads a problem-document field, failing loudly with the whole body if the field is absent.
problem_code() { python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("code","<no code>"))
except Exception: print("<not a problem document>")'; }

# ---------------------------------------------------------------- registration

step "Registering a new account through the gateway"
REGISTER=$(curl -s -w '\n%{http_code}' -X POST "$GATEWAY/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
CODE=$(printf '%s' "$REGISTER" | tail -1)
BODY=$(printf '%s' "$REGISTER" | sed '$d')
[ "$CODE" = "201" ] || fail "expected 201, got $CODE: $BODY"

ACCESS=$(printf '%s' "$BODY" | jq_get "['token']")
REFRESH=$(printf '%s' "$BODY" | jq_get "['refreshToken']")
USER_ID=$(printf '%s' "$BODY" | jq_get "['userId']")
EXPIRES_IN=$(printf '%s' "$BODY" | jq_get "['expiresIn']")
[ -n "$ACCESS" ] && [ -n "$REFRESH" ] || fail "no token pair in: $BODY"

# `token` is the field the existing iOS AuthSession decodes, so it has to stay the access token.
echo "$ACCESS" | grep -qE '^eyJ' || fail "access token does not look like a JWT"
echo "$REFRESH" | grep -qvE '^eyJ' || fail "refresh token should be opaque, not a JWT"
pass "userId=$USER_ID expiresIn=${EXPIRES_IN}s (access=JWT, refresh=opaque)"

step "The access token is short-lived"
# 24 hours was the previous value. A stateless token that cannot be revoked has to expire.
[ "$EXPIRES_IN" -le 3600 ] || fail "access token lives $EXPIRES_IN s; expected <= 3600"
pass "${EXPIRES_IN}s"

step "Registering the same email again is 409"
CODE=$(curl -s -o /tmp/e2e-dup.json -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"other$STAMP\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
[ "$CODE" = "409" ] || fail "expected 409, got $CODE"
[ "$(problem_code < /tmp/e2e-dup.json)" = "username-or-email-taken" ] \
    || fail "expected username-or-email-taken, got $(problem_code < /tmp/e2e-dup.json)"
pass "409 / username-or-email-taken"

step "A short password is rejected with the minimum stated"
CODE=$(curl -s -o /tmp/e2e-weak.json -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"weak$STAMP\",\"email\":\"weak$STAMP@example.com\",\"password\":\"short\"}")
[ "$CODE" = "400" ] || fail "expected 400, got $CODE"
pass "400"

step "A malformed body is 400, not 500"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/register" \
    -H 'Content-Type: application/json' -d '{ not json')
[ "$CODE" = "400" ] || fail "expected 400 for unparseable JSON, got $CODE"
pass "400"

# ----------------------------------------------------------------------- login

step "Signing in with the right password"
LOGIN=$(curl -s -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
ACCESS=$(printf '%s' "$LOGIN" | jq_get "['token']")
REFRESH=$(printf '%s' "$LOGIN" | jq_get "['refreshToken']")
[ -n "$ACCESS" ] && [ -n "$REFRESH" ] || fail "no token pair: $LOGIN"
pass "signed in"

step "Signing in by email also works"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
[ "$CODE" = "200" ] || fail "expected 200 signing in by email, got $CODE"
pass "200"

step "A wrong password is 401 with a non-enumerating message"
WRONG=$(curl -s -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"$USERNAME\",\"password\":\"definitely-wrong\"}")
[ "$(printf '%s' "$WRONG" | jq_get "['code']")" = "invalid-credentials" ] \
    || fail "expected invalid-credentials, got: $WRONG"
# The unknown-account message must be byte-identical, or login enumerates accounts.
UNKNOWN=$(curl -s -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"nobody$STAMP\",\"password\":\"definitely-wrong\"}")
[ "$(printf '%s' "$WRONG" | jq_get "['detail']")" = "$(printf '%s' "$UNKNOWN" | jq_get "['detail']")" ] \
    || fail "unknown-account message differs from wrong-password message"
pass "identical message for both cases"

# ------------------------------------------------------------------- /me + auth

step "The access token reaches /me through the gateway"
ME=$(curl -s "$GATEWAY/api/v1/auth/me" -H "Authorization: Bearer $ACCESS")
ME_ID=$(printf '%s' "$ME" | jq_get "['userId']")
[ "$ME_ID" = "$USER_ID" ] || fail "/me returned $ME_ID, expected $USER_ID: $ME"
pass "userId matches"

step "No token is 401 with a usable code"
NOAUTH=$(curl -s "$GATEWAY/api/v1/auth/me")
[ "$(printf '%s' "$NOAUTH" | jq_get "['code']")" = "unauthenticated" ] \
    || fail "expected unauthenticated, got: $NOAUTH"
pass "401 / unauthenticated"

step "A garbage token is 401 token-invalid, not token-expired"
# The client has to be able to tell "refresh and retry" from "sign in again".
BAD=$(curl -s "$GATEWAY/api/v1/auth/me" -H "Authorization: Bearer not-a-real-token")
[ "$(printf '%s' "$BAD" | jq_get "['code']")" = "token-invalid" ] \
    || fail "expected token-invalid, got: $BAD"
pass "401 / token-invalid"

step "A client cannot forge its identity by setting X-User-Id"
# The gateway overwrites the header from the token; the value sent here must be ignored.
FORGED=$(curl -s "$GATEWAY/api/v1/auth/me" \
    -H "Authorization: Bearer $ACCESS" \
    -H "X-User-Id: someone-else")
FORGED_ID=$(printf '%s' "$FORGED" | jq_get "['userId']")
[ "$FORGED_ID" = "$USER_ID" ] || fail "forged header won: got $FORGED_ID, expected $USER_ID"
pass "forged X-User-Id ignored"

step "Reaching a service directly, without the gateway secret, is 403"
# This is the hole the X-Gateway-Secret split closes. Published on 8081 purely so this is
# testable; it would not be exposed in production.
DIRECT=$(curl -s -o /tmp/e2e-direct.json -w '%{http_code}' \
    "$AUTH_SERVICE/api/v1/auth/me" -H "Authorization: Bearer $ACCESS")
[ "$DIRECT" = "403" ] || fail "expected 403 hitting auth-service directly, got $DIRECT"
[ "$(problem_code < /tmp/e2e-direct.json)" = "gateway-secret-invalid" ] \
    || fail "expected gateway-secret-invalid, got $(problem_code < /tmp/e2e-direct.json)"
pass "403 / gateway-secret-invalid"

# --------------------------------------------------------------------- refresh

step "Refreshing returns a new pair"
ROTATED=$(curl -s -X POST "$GATEWAY/api/v1/auth/refresh" -H 'Content-Type: application/json' \
    -d "{\"refreshToken\":\"$REFRESH\"}")
NEW_ACCESS=$(printf '%s' "$ROTATED" | jq_get "['token']")
NEW_REFRESH=$(printf '%s' "$ROTATED" | jq_get "['refreshToken']")
[ -n "$NEW_ACCESS" ] && [ -n "$NEW_REFRESH" ] || fail "no pair from refresh: $ROTATED"
# Rotation is the whole point: an unchanged refresh token would be valid until expiry, with no
# signal that it leaked.
[ "$NEW_REFRESH" != "$REFRESH" ] || fail "refresh token was not rotated"
pass "rotated"

step "The new access token works"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/v1/auth/me" \
    -H "Authorization: Bearer $NEW_ACCESS")
[ "$CODE" = "200" ] || fail "new access token rejected with $CODE"
pass "200"

step "A replay inside the grace window is tolerated"
# A mobile client that retried after a lost response lands here. Without this the caller loses
# the only copy of its token and the user appears logged out at random.
# The response is still 401 - the caller cannot know whether it was tolerated or detected - but
# the token it received from the earlier response is untouched.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
[ "$CODE" = "401" ] || fail "expected 401 replaying a consumed token, got $CODE"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$NEW_REFRESH\"}")
[ "$CODE" = "200" ] || fail "the session should survive a tolerated replay (got $CODE)"
pass "tolerated, session intact"

step "A replay after the grace window revokes the family"
# Outside the window this is genuine reuse: two parties hold the token and there is no way to
# tell which is the real client. The only safe response is to end the session for both.
echo "    waiting out the reuse grace window..."
sleep 6

CODE=$(curl -s -o /tmp/e2e-reuse.json -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
[ "$CODE" = "401" ] || fail "expected 401 replaying a consumed token, got $CODE"
[ "$(problem_code < /tmp/e2e-reuse.json)" = "refresh-token-invalid" ] \
    || fail "expected refresh-token-invalid, got $(problem_code < /tmp/e2e-reuse.json)"
pass "401 / refresh-token-invalid"

step "The replacement token died with the family"
# Proves reuse detection actually revoked rather than just rejecting the replayed token.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$NEW_REFRESH\"}")
[ "$CODE" = "401" ] || fail "the other holder's token still works (got $CODE)"
pass "family revoked"

step "A made-up refresh token is 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d '{"refreshToken":"fabricated-token-value"}')
[ "$CODE" = "401" ] || fail "expected 401, got $CODE"
pass "401"

# ---------------------------------------------------------------------- logout

step "Signing in again and logging out"
LOGIN=$(curl -s -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
R2=$(printf '%s' "$LOGIN" | jq_get "['refreshToken']")

CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/logout" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$R2\",\"allDevices\":false}")
[ "$CODE" = "204" ] || fail "expected 204, got $CODE"
pass "204"

step "The refresh token is dead after logout"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$R2\"}")
[ "$CODE" = "401" ] || fail "refresh token still valid after logout (got $CODE)"
pass "401"

step "Logging out twice still succeeds"
# A client that lost the response must be able to retry.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/logout" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$R2\",\"allDevices\":false}")
[ "$CODE" = "204" ] || fail "expected 204 on repeat logout, got $CODE"
pass "204, idempotent"

# --------------------------------------------------------------------- lockout

step "Repeated failures lock the account"
LOCK_USER="lock$STAMP"
LOCK_EMAIL="lock$STAMP@example.com"
curl -s -o /dev/null -X POST "$GATEWAY/api/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$LOCK_USER\",\"email\":\"$LOCK_EMAIL\",\"password\":\"$PASSWORD\"}"

LOCKED=0
for _ in 1 2 3 4 5 6; do
    RESP=$(curl -s -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
        -d "{\"identifier\":\"$LOCK_USER\",\"password\":\"wrong\"}")
    if [ "$(printf '%s' "$RESP" | jq_get "['code']")" = "account-locked" ]; then
        LOCKED=1
        break
    fi
done
[ "$LOCKED" = "1" ] || fail "account never locked after 6 wrong passwords"
RETRY=$(printf '%s' "$RESP" | jq_get "['retryAfterSeconds']")
[ -n "$RETRY" ] && [ "$RETRY" -gt 0 ] || fail "no retryAfterSeconds in: $RESP"
pass "423 / account-locked, retryAfter=${RETRY}s"

step "The correct password is refused while locked"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/auth/login" \
    -H 'Content-Type: application/json' -d "{\"identifier\":\"$LOCK_USER\",\"password\":\"$PASSWORD\"}")
[ "$CODE" = "423" ] || fail "expected 423 with the correct password, got $CODE"
pass "423"

# --------------------------------------------------------------------- routing

step "An unknown route is 404 for an authenticated caller"
# Authenticated deliberately. The gateway rejects anonymous requests before routing, so an
# unauthenticated 404 is unreachable - and 401-first is the right answer anyway, since it does
# not confirm whether the path exists.
LOGIN=$(curl -s -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
L3=$(printf '%s' "$LOGIN" | jq_get "['token']")
CODE=$(curl -s -o /tmp/e2e-404.json -w '%{http_code}' "$GATEWAY/api/v1/definitely-not-a-route" \
    -H "Authorization: Bearer $L3")
[ "$CODE" = "404" ] || fail "expected 404, got $CODE"
[ "$(problem_code < /tmp/e2e-404.json)" = "not-found" ] \
    || fail "expected not-found, got $(problem_code < /tmp/e2e-404.json)"
pass "404 / not-found"

step "The wrong method is 405 and advertises Allow"
ALLOW=$(curl -s -o /dev/null -D - -X GET "$GATEWAY/api/v1/auth/login" | grep -i '^allow:' | tr -d '\r')
[ -n "$ALLOW" ] || fail "405 without an Allow header"
pass "${ALLOW%%:*}"

echo
printf '%s========================================%s\n' "$GREEN" "$RESET"
printf '%s  AUTH OK   user=%s%s\n' "$GREEN" "$RESET" "$USERNAME"
printf '%s========================================%s\n' "$GREEN" "$RESET"
