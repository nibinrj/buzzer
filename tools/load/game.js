// One whole Buzzer game under load, through the gateway, the way the test client plays it.
//
//   setup    a fresh host account, a published quiz of QUESTIONS questions, a session (its room code)
//   players  PLAYERS virtual users, one game each: guest token → join → STOMP → answer every question 0–300 ms
//            after it arrives (correct with probability P_CORRECT) → leave once the game has ENDED
//   host     1 virtual user: waits until everyone has joined, then start, and QUESTIONS × (reveal, next), then end
//
// Run it with tools/load/run-game.ps1, which also saves the summary. By hand:
//   k6 run -e PLAYERS=50 tools/load/game.js
//
// Custom metrics (all in ms):
//   ack_latency               answer SENT → its ack received. One clock (k6's), so exact.
//   question_fanout           question received − the moment the server opened it (deadline − time limit).
//                             Two clocks: k6's and Redis's (it set the deadline), so it includes their offset.
//                             NOT reliable locally: Docker Desktop's VM clock drifts against Windows by hundreds of
//                             ms within one game (seen 2026-10-03), so no single offset corrects it. Use
//                             question_broadcast locally; this one is meant for AWS, where both clocks run NTP.
//   question_broadcast        host only: start/next SENT → the host's own copy of the question received. One clock.
//   redis_clock_offset        host only: Redis's clock minus k6's, estimated per question as the server's open time
//                             minus the midpoint of that round trip (accurate to ± half of question_broadcast).
//   leaderboard_push_latency  my accepted correct answer → the first leaderboard push that shows my points went up.
//                             Only top-10 players get samples: only the top 10 is pushed.
//
// Never logs a token: tokens go into request headers and the CONNECT frame only.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomBytes } from 'k6/crypto';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { WebSocket } from 'k6/websockets';
import * as stomp from './stomp.js';

// --- Settings (k6 -e NAME=value) --------------------------------------------------------------------------------
const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const WS_URL = BASE_URL.replace(/^http/, 'ws') + '/ws';
const PLAYERS = intEnv('PLAYERS', 50);
const QUESTIONS = intEnv('QUESTIONS', 20);
const TIME_LIMIT_S = intEnv('TIME_LIMIT_S', 20); // quiz-service allows 5–60
const QUESTION_MS = intEnv('QUESTION_MS', 3000); // how long the host leaves a question open before revealing it
const REVEAL_MS = intEnv('REVEAL_MS', 1000); // reveal → next question
const JOIN_RAMP_S = intEnv('JOIN_RAMP_S', Math.max(5, Math.ceil(PLAYERS / 10))); // joins spread over this long
const START_GRACE_MS = intEnv('START_GRACE_MS', 3000); // after the last join, time to open sockets and subscribe
const LINGER_MS = intEnv('LINGER_MS', 3000); // after ENDED, keep listening for the last leaderboard pushes
const ANSWER_DELAY_MS = 300;
const P_CORRECT = Number(__ENV.P_CORRECT || 0.7);

const GAME_MS = QUESTIONS * (QUESTION_MS + REVEAL_MS);
const MAX_DURATION_S = Math.ceil(JOIN_RAMP_S + 60 + GAME_MS / 1000 + 60);

export const options = {
  setupTimeout: '120s',
  scenarios: {
    players: {
      executor: 'per-vu-iterations',
      exec: 'player',
      vus: PLAYERS,
      iterations: 1,
      maxDuration: `${MAX_DURATION_S}s`,
    },
    host: {
      executor: 'per-vu-iterations',
      exec: 'host',
      vus: 1,
      iterations: 1,
      maxDuration: `${MAX_DURATION_S}s`,
    },
  },
  thresholds: {
    ack_latency: [`p(95)<${intEnv('ACK_P95_MS', 250)}`],
    question_fanout: [`p(95)<${intEnv('FANOUT_P95_MS', 500)}`],
    leaderboard_push_latency: [`p(95)<${intEnv('PUSH_P95_MS', 2000)}`],
    checks: ['rate>0.99'],
  },
};

// --- Metrics ------------------------------------------------------------------------------------------------------
const ackLatency = new Trend('ack_latency', true);
const questionFanout = new Trend('question_fanout', true);
const leaderboardPushLatency = new Trend('leaderboard_push_latency', true);
const questionBroadcast = new Trend('question_broadcast', true);
const redisClockOffset = new Trend('redis_clock_offset', true);
const answers = new Counter('answers'); // tagged outcome: ACCEPTED or the rejection reason
const questionsReceived = new Counter('questions_received');
const stompErrors = new Counter('stomp_errors'); // ERROR frames and replies on /user/queue/errors
const rateLimited = new Counter('rate_limited'); // 429s from the gateway (retried)

// --- setup: host, quiz, session -----------------------------------------------------------------------------------
export function setup() {
  // A throwaway host. What setup returns is written into k6's summary files (setup_data), so it must hold no
  // secret: not the token, not the password. The password comes from HOST_PASSWORD, which run-game.ps1 sets to a
  // new random value for each game, and the host VU logs in again with it.
  if (!__ENV.HOST_PASSWORD) {
    exec.test.abort('Set HOST_PASSWORD (any 8-72 characters). run-game.ps1 makes a random one per game.');
  }
  const hostEmail = `k6-${hex(4)}@test.dev`;
  expect(post('/api/auth/register', { email: hostEmail, password: __ENV.HOST_PASSWORD }), 'register host');
  const hostToken = logIn(hostEmail);

  // Every quiz change returns the quiz with its new version, which the next change must send back.
  let quiz = expect(post('/api/quizzes', { title: `Load ${new Date().toISOString()}` }, hostToken),
    'create quiz').json();
  const correct = [];
  for (let i = 0; i < QUESTIONS; i++) {
    correct.push(i % 4); // the correct option moves around, as in a real quiz
    const options = [0, 1, 2, 3].map((o) => ({ text: `Option ${o}`, correct: o === i % 4 }));
    const body = { text: `Question ${i + 1}?`, timeLimitSeconds: TIME_LIMIT_S, version: quiz.version, options };
    quiz = expect(post(`/api/quizzes/${quiz.id}/questions`, body, hostToken), 'add question').json();
  }
  quiz = expect(post(`/api/quizzes/${quiz.id}/publish`, { version: quiz.version }, hostToken), 'publish').json();

  const session = expect(post('/api/sessions', { quizId: quiz.id }, hostToken), 'create session').json();
  console.log(`session ${session.sessionId}, room ${session.roomCode}: ${PLAYERS} players, ${QUESTIONS} questions`);
  return { hostEmail, sessionId: session.sessionId, roomCode: session.roomCode, correct };
}

function logIn(email) {
  const login = post('/api/auth/login', { email, password: __ENV.HOST_PASSWORD });
  return expect(login, 'log in host').json('accessToken');
}

// --- players ------------------------------------------------------------------------------------------------------
export function player(data) {
  const index = exec.scenario.iterationInTest; // 0 … PLAYERS-1
  sleep((index * JOIN_RAMP_S) / PLAYERS);

  const guest = post('/api/auth/guest', { nickname: `p${index}` });
  if (!check(guest, { 'guest token': (r) => r.status === 200 })) {
    return;
  }
  const token = guest.json('accessToken');
  const joined = post('/api/sessions/join', { roomCode: data.roomCode }, token);
  if (!check(joined, { joined: (r) => r.status === 201 || r.status === 200 })) {
    return;
  }
  const me = joined.json('playerId');
  const sessionId = data.sessionId;
  const topic = (name) => `/topic/sessions/${sessionId}/${name}`;

  let pending = null; // the answer in flight: {questionId, sentAt, correct}
  let correctAcceptedAt = null; // set by an accepted correct answer, cleared once a push shows the new points
  let myPoints; // my points in the last push that listed me; undefined while I'm not in the top 10
  let leaderboardVersion = -1;
  let seen = 0;
  let ended = false;

  const ws = new WebSocket(WS_URL);
  ws.onopen = () => ws.send(stomp.connect(token));
  ws.onerror = (e) => {
    stompErrors.add(1, { kind: 'socket' });
    console.error(`player ${index}: socket error ${e.error}`);
  };
  ws.onclose = () => {
    check(seen, { 'player saw every question': (n) => n === QUESTIONS });
    check(ended, { 'player saw the game end': (done) => done });
  };
  ws.onmessage = (event) => {
    for (const frame of stomp.parse(event.data)) {
      if (frame.command === 'CONNECTED') {
        ws.send(stomp.subscribe('question', topic('question')));
        ws.send(stomp.subscribe('status', topic('status')));
        ws.send(stomp.subscribe('leaderboard', topic('leaderboard')));
        ws.send(stomp.subscribe('ack', '/user/queue/answer-ack'));
        ws.send(stomp.subscribe('errors', '/user/queue/errors'));
      } else if (frame.command === 'ERROR') {
        stompErrors.add(1, { kind: 'frame' });
        console.error(`player ${index}: ERROR ${frame.headers.message}`);
      } else if (frame.command === 'MESSAGE') {
        onMessage(frame.headers.subscription, JSON.parse(frame.body), Date.now());
      }
    }
  };

  function onMessage(subscription, body, now) {
    switch (subscription) {
      case 'question': {
        seen++;
        questionsReceived.add(1);
        questionFanout.add(now - (epochMs(body.deadline) - body.timeLimitSeconds * 1000));
        setTimeout(() => answer(body), Math.random() * ANSWER_DELAY_MS);
        break;
      }
      case 'ack': {
        if (pending === null || body.questionId !== pending.questionId) {
          break;
        }
        ackLatency.add(now - pending.sentAt);
        answers.add(1, { outcome: body.accepted ? 'ACCEPTED' : body.reason });
        if (body.accepted && pending.correct) {
          correctAcceptedAt = now;
        }
        pending = null;
        break;
      }
      case 'leaderboard': {
        if (body.version <= leaderboardVersion) {
          break; // an older push overtaken by a newer one
        }
        leaderboardVersion = body.version;
        const line = body.top.find((l) => l.playerId === me);
        if (!line) {
          break;
        }
        if (correctAcceptedAt !== null && (myPoints === undefined || line.points > myPoints)) {
          leaderboardPushLatency.add(now - correctAcceptedAt);
          correctAcceptedAt = null;
        }
        myPoints = line.points;
        break;
      }
      case 'status': {
        if (body.status === 'ENDED') {
          ended = true;
          setTimeout(() => {
            ws.send(stomp.disconnect());
            ws.close();
          }, LINGER_MS);
        }
        break;
      }
      case 'errors': {
        stompErrors.add(1, { kind: 'reply' });
        console.error(`player ${index}: ${body.title}: ${body.detail}`);
        break;
      }
    }
  }

  function answer(question) {
    const right = data.correct[question.index];
    const correct = Math.random() < P_CORRECT;
    const optionId = correct ? right : (right + 1) % question.options.length;
    pending = { questionId: question.questionId, sentAt: Date.now(), correct };
    ws.send(stomp.send(`/app/sessions/${sessionId}/answer`, { questionId: question.questionId, optionId }));
  }
}

// --- host ---------------------------------------------------------------------------------------------------------
export function host(data) {
  const token = logIn(data.hostEmail);
  waitForPlayers(data, token);
  sleep(START_GRACE_MS / 1000);

  const command = (name) => stomp.send(`/app/sessions/${data.sessionId}/${name}`);
  let index = 0;
  let commandSentAt = null; // when the last start/next went out

  const ws = new WebSocket(WS_URL);
  ws.onopen = () => ws.send(stomp.connect(token));
  ws.onerror = (e) => {
    stompErrors.add(1, { kind: 'socket' });
    console.error(`host: socket error ${e.error}`);
  };
  ws.onmessage = (event) => {
    for (const frame of stomp.parse(event.data)) {
      if (frame.command === 'CONNECTED') {
        ws.send(stomp.subscribe('question', `/topic/sessions/${data.sessionId}/question`));
        ws.send(stomp.subscribe('errors', '/user/queue/errors'));
        commandSentAt = Date.now();
        ws.send(command('start')); // also opens question 0
        setTimeout(reveal, QUESTION_MS);
      } else if (frame.command === 'MESSAGE' && frame.headers.subscription === 'question') {
        onOwnQuestion(JSON.parse(frame.body), Date.now());
      } else if (frame.command === 'ERROR' || frame.command === 'MESSAGE') {
        // any other MESSAGE is on /user/queue/errors: a command was refused
        stompErrors.add(1, { kind: frame.command === 'ERROR' ? 'frame' : 'reply' });
        console.error(`host: ${frame.command} ${frame.headers.message || frame.body}`);
      }
    }
  };

  // The server read Redis's clock (the question's open time) somewhere between our send and our receipt.
  function onOwnQuestion(question, receivedAt) {
    if (commandSentAt === null) {
      return;
    }
    const openedAt = epochMs(question.deadline) - question.timeLimitSeconds * 1000;
    questionBroadcast.add(receivedAt - commandSentAt);
    redisClockOffset.add(openedAt - (commandSentAt + receivedAt) / 2);
    commandSentAt = null;
  }

  function reveal() {
    ws.send(command('reveal'));
    index++;
    setTimeout(index < QUESTIONS ? next : end, REVEAL_MS);
  }

  function next() {
    commandSentAt = Date.now();
    ws.send(command('next'));
    setTimeout(reveal, QUESTION_MS);
  }

  function end() {
    ws.send(command('end'));
    setTimeout(() => {
      ws.send(stomp.disconnect());
      ws.close();
    }, 1000);
  }
}

/** Polls the session's state until every player has joined, or until the join ramp is a minute overdue. */
function waitForPlayers(data, token) {
  const giveUpAt = Date.now() + (JOIN_RAMP_S + 60) * 1000;
  let joined = 0;
  while (Date.now() < giveUpAt) {
    const state = withRetry(() => http.get(`${BASE_URL}/api/sessions/${data.sessionId}/state`,
      { headers: { Authorization: `Bearer ${token}` } }));
    joined = state.status === 200 ? state.json('players').length : joined;
    if (joined >= PLAYERS) {
      break;
    }
    sleep(1);
  }
  check(joined, { 'every player joined before the start': (n) => n === PLAYERS });
}

// --- helpers ------------------------------------------------------------------------------------------------------
function post(path, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return withRetry(() => http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers }));
}

/**
 * The gateway rate-limits per client IP, and every virtual user shares this machine's IP. A 429 is retried after
 * 0.5, 1, 2, 4 s and counted, so a run without the raised load-test limits shows it in rate_limited.
 */
function withRetry(request) {
  let response = request();
  for (let wait = 0.5; response.status === 429 && wait <= 4; wait *= 2) {
    rateLimited.add(1);
    sleep(wait);
    response = request();
  }
  return response;
}

/** setup can't continue without each step: anything but 2xx stops the whole test. */
function expect(response, what) {
  if (response.status < 200 || response.status > 299) {
    exec.test.abort(`${what}: HTTP ${response.status} ${response.body}`);
  }
  return response;
}

/** The deadline as epoch ms, whether Jackson wrote the Instant as ISO-8601 text or as a number of seconds. */
function epochMs(instant) {
  return typeof instant === 'number' ? instant * 1000 : Date.parse(instant);
}

function intEnv(name, fallback) {
  return __ENV[name] ? parseInt(__ENV[name], 10) : fallback;
}

function hex(bytes) {
  return Array.from(new Uint8Array(randomBytes(bytes)), (b) => b.toString(16).padStart(2, '0')).join('');
}
