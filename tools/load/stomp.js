// Just enough STOMP 1.2 for Buzzer's load test, on top of a k6 WebSocket. k6 has no STOMP client, and the protocol
// is small: a frame is a command line, header lines, a blank line, a body, and a NUL byte.
//
//   SEND\n
//   destination:/app/sessions/<id>/answer\n
//   content-type:application/json\n
//   \n
//   {"questionId":"...","optionId":2}\0
//
// Spring sends one frame per WebSocket message. Heart-beats would be bare newlines, but CONNECT turns them off.

const NUL = '\u0000';

/**
 * One frame as a string. No header escaping: every value we send (destinations, UUIDs, "Bearer <jwt>") is free of
 * the characters STOMP 1.2 escapes (newline, colon, backslash, carriage return).
 */
export function frame(command, headers = {}, body = '') {
  let text = command + '\n';
  for (const [name, value] of Object.entries(headers)) {
    text += `${name}:${value}\n`;
  }
  return text + '\n' + body + NUL;
}

/**
 * The CONNECT frame. The token goes in a frame header because the handshake is a plain GET that session-service
 * doesn't authenticate (JwtConnectInterceptor reads it here). heart-beat 0,0: a test game lasts ~2 minutes, so
 * neither side needs to prove it is alive.
 */
export function connect(token) {
  return frame('CONNECT', {
    'accept-version': '1.2',
    'heart-beat': '0,0',
    Authorization: `Bearer ${token}`,
  });
}

export function subscribe(id, destination) {
  return frame('SUBSCRIBE', { id, destination });
}

export function send(destination, payload) {
  return payload === undefined
    ? frame('SEND', { destination })
    : frame('SEND', { destination, 'content-type': 'application/json' }, JSON.stringify(payload));
}

export function disconnect() {
  return frame('DISCONNECT');
}

/** Every frame in one WebSocket message, as {command, headers, body}. */
export function parse(data) {
  const frames = [];
  for (let chunk of String(data).split(NUL)) {
    chunk = chunk.replace(/^[\r\n]+/, ''); // heart-beats, and the EOL a server may put between frames
    if (!chunk) {
      continue;
    }
    const blank = chunk.indexOf('\n\n');
    const head = blank < 0 ? chunk : chunk.slice(0, blank);
    const lines = head.split('\n').map((line) => line.replace(/\r$/, ''));
    const headers = {};
    for (const line of lines.slice(1)) {
      const colon = line.indexOf(':');
      const name = line.slice(0, colon);
      // STOMP 1.2: when a header repeats, the first one counts.
      if (colon > 0 && !(name in headers)) {
        headers[name] = unescape(line.slice(colon + 1));
      }
    }
    frames.push({ command: lines[0], headers, body: blank < 0 ? '' : chunk.slice(blank + 2) });
  }
  return frames;
}

// The reverse of STOMP 1.2's header escaping. Spring escapes, for example, a colon in an error message header.
function unescape(value) {
  return value.replace(/\\([rnc\\])/g, (_, c) => ({ r: '\r', n: '\n', c: ':', '\\': '\\' })[c]);
}
