#!/usr/bin/env sh
# .env 를 처음 만들 때 사용: 도메인만 물어보고 비밀번호·키는 임의 값으로 채운다.
# 사용법: sh init-env.sh
set -eu
cd "$(dirname "$0")"
if [ -f .env ]; then echo ".env 가 이미 있습니다. 덮어쓰지 않습니다."; exit 1; fi

printf '업무 화면 도메인 (예: hub.example.com): '; read -r APP_DOMAIN
printf '화상 회의 도메인 (예: meet.example.com): '; read -r MEET_DOMAIN
printf '인증서 안내 받을 메일: '; read -r ACME_EMAIL

rnd() { openssl rand -hex "$1"; }
ADMIN_PW=$(rnd 9); USER_PW=$(rnd 9)

sed -e "s|^APP_DOMAIN=.*|APP_DOMAIN=$APP_DOMAIN|" \
    -e "s|^MEET_DOMAIN=.*|MEET_DOMAIN=$MEET_DOMAIN|" \
    -e "s|^ACME_EMAIL=.*|ACME_EMAIL=$ACME_EMAIL|" \
    -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(rnd 24)|" \
    -e "s|^JWT_SECRET=.*|JWT_SECRET=$(rnd 32)|" \
    -e "s|^OFFICE_ADMIN_PASSWORD=.*|OFFICE_ADMIN_PASSWORD=$ADMIN_PW|" \
    -e "s|^OFFICE_USER_PASSWORD=.*|OFFICE_USER_PASSWORD=$USER_PW|" \
    -e "s|^LIVEKIT_API_SECRET=.*|LIVEKIT_API_SECRET=$(rnd 32)|" \
    .env.example > .env
chmod 600 .env

echo
echo ".env 를 만들었습니다. 처음 로그인 계정:"
echo "  admin / $ADMIN_PW"
echo "  user  / $USER_PW"
echo "(이 비밀번호는 .env 에도 저장되어 있습니다. 첫 로그인 후 바꾸세요.)"
