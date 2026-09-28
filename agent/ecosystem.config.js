// pm2 — 맥마다 하나. 127.0.0.1 에만 바인딩하고 tailnet 노출은 `tailscale serve --https=8797` 가 한다.
// 기계 이름·보내기 모드·상대 맥 주소는 맥마다 다르므로 환경변수로 준다(agent/.env.example 참고):
//   CMR_MACHINE=laptop|home  CMR_SEND=off|allowlist|on  CMR_PEER_URL=https://상대-맥:8797 CMR_PEER_NAME=홈맥
//   pm2 start agent/ecosystem.config.js --update-env
const path = require('path');
const DIR = __dirname;
module.exports = {
  apps: [{
    name: 'cmux-mobile-agent',
    cwd: DIR,
    script: path.join(DIR, '.venv/bin/python'),
    args: `-m uvicorn server:app --app-dir ${DIR} --host 127.0.0.1 --port 7797`,
    interpreter: 'none',
    autorestart: true,
    // 백스톱이지 정상 피크를 조이는 장치가 아니다(관제실 150M 사고 참조) — 넉넉히.
    max_memory_restart: '512M',
    env: {
      PYTHONUNBUFFERED: '1',
      CMR_MACHINE: process.env.CMR_MACHINE || 'laptop',
      CMR_SEND: process.env.CMR_SEND || 'off',
      CMR_SEND_ALLOW: process.env.CMR_SEND_ALLOW || '',
      CMR_PEER_URL: process.env.CMR_PEER_URL || '',
      CMR_PEER_NAME: process.env.CMR_PEER_NAME || '',
      CMR_SCREENLOG: process.env.CMR_SCREENLOG || 'on',
    },
    out_file: path.join(DIR, 'data/pm2-out.log'),
    error_file: path.join(DIR, 'data/pm2-err.log'),
  }],
};
