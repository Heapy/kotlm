# kotlm

An OpenAI-compatible proxy in front of a Codex subscription. One service owns the authentication session, while projects such as kotomka, QA environments, sql-nastya, kotbot, and tgpt access it through a regular OpenAI client with their own keys.

```text
kotbot ─┐
tgpt   ─┤   Bearer <project key>    ┌ /v1/responses        ┌ OAuth tokens on disk
qa     ─┼──────────────────────────▶ │ /v1/chat/completions │ refresh rotation
sql    ─┘                            └ /v1/models           └ limits, budgets, metrics
                                              │
                                              ▼
                                   chatgpt.com/backend-api/codex
```

## Why

A Codex subscription uses an OAuth session rather than an API key: a short-lived access token, a refresh token that **rotates after every refresh**, and the headers that the backend expects from Codex CLI. This state cannot be copied across projects because two clients sharing one refresh token invalidate each other's sessions. kotlm keeps the session in one place and gives projects a conventional HTTP API.

## Quick start

```bash
cp .env.example .env                      # project keys and the administrator key
docker compose up -d
```

The proxy refuses to start without project keys. An unauthenticated open port would expose the subscription to anyone who could reach it.

## Subscription authorization

Tokens live in a file. The proxy reads `KOTLM_STATE_FILE` when it exists; otherwise it reads the `KOTLM_AUTH_FILE` secret and writes only to the state file from then on. This is intentional: the secret is mounted read-only and used for bootstrapping, while the rotated refresh token must survive restarts.

**Log in from the server** (requires `KOTLM_ADMIN_KEY`):

```bash
curl -sX POST -H "Authorization: Bearer $KOTLM_ADMIN_KEY" http://kotlm:8080/auth/device/start
# {"id":"...","userCode":"ABCD-1234","verificationUrl":"https://auth.openai.com/codex/device", ...}
```

Open the link on a phone, enter the code, and then poll for completion:

```bash
curl -sX POST -H "Authorization: Bearer $KOTLM_ADMIN_KEY" \
     -H 'Content-Type: application/json' -d '{"id":"..."}' \
     http://kotlm:8080/auth/device/poll
# {"status":"pending","retryAfterSeconds":5} → {"status":"complete"}
```

**Log in by copying a file**: place the Codex CLI `auth.json` at `secrets/codex_auth.json`. Both the flat `{"tokens":{...}}` form and the wrapped `{"providers":{"openai-codex":{...}}}` form are supported. Do not use the copy at the same time as a live Codex CLI session: there is only one refresh token, and the first exchange invalidates the other instance.

Check subscription state with `GET /auth/status` using the administrator key, or use `GET /health` without secrets.

## API

| Route | Purpose |
| --- | --- |
| `POST /v1/responses` | Primary endpoint: the OpenAI Responses contract, including `text.format` with a strict JSON schema |
| `POST /v1/chat/completions` | Compatibility for existing SDKs: messages are converted to Responses input and the result is converted back |
| `GET /v1/models` | List of allowed models |
| `GET /health` | Readiness; returns 503 until the subscription is authorized |
| `GET /live` | Liveness probe for the Docker health check; returns 200 while the process is alive |
| `GET /metrics` | Prometheus metrics |

Requests are authorized with the `Authorization: Bearer <project key>` header.

The proxy applies these changes to every request:

- `store` is always `false`, so conversations are not retained by the provider;
- `max_output_tokens` is capped by `KOTLM_MAX_OUTPUT_TOKENS`;
- provider tools (`tools`), references to previous responses (`previous_response_id`, `conversation`, `prompt`), and non-text input are rejected because their token cost cannot be determined from the request body;
- models outside `KOTLM_ALLOWED_MODELS` are rejected so a typo cannot consume the subscription;
- a string in `input` is wrapped in a list because the OpenAI contract permits a string while Codex responds with `Input must be a list`;
- `max_output_tokens` **is not sent to the provider** because the subscription responds with `Unsupported parameter`. The value is checked for validity and then discarded, so this parameter cannot limit response length.

The model available to a ChatGPT account is `gpt-5.6-sol`. Names such as `gpt-5.6`, `gpt-5-codex`, and `gpt-5.6-codex` are rejected by the subscription with `model is not supported when using Codex with a ChatGPT account`.

`stream: true` is supported, but events are returned together at the end rather than as they are generated. This is due to Ktor writing a streaming response body lazily, after the provider connection has already closed, so the stream is read in full during the request. The event format remains genuine SSE and client SDKs parse it normally.

## Project keys, limits, and budgets

```json
{
  "clients": [
    { "name": "sql-nastya", "key": "…", "requestsPerMinute": 30, "dailyTokens": 300000 },
    { "name": "kotbot", "key": "…", "requestsPerMinute": 60 }
  ]
}
```

- Every project has its own key, making usage attributable and allowing one project to be revoked without affecting the others.
- `requestsPerMinute` is a sliding in-memory window that starts fresh after a restart.
- `dailyTokens` is a daily ceiling. Usage is persisted to `KOTLM_USAGE_FILE` and survives restarts; otherwise restarting would reset the limit. Use `0` for no limit.

Both limits are checked before the subscription is contacted.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `KOTLM_HOST`, `KOTLM_PORT` | `0.0.0.0`, `8080` | Listening address |
| `KOTLM_CLIENTS_FILE` / `KOTLM_CLIENTS` | — | Project keys as a file or a JSON string; startup fails when neither is provided |
| `KOTLM_ADMIN_KEY` | — | Key for `/auth/*`; device-code login is disabled when absent |
| `KOTLM_AUTH_FILE` | `/run/secrets/codex_auth` | Read-only secret file containing tokens |
| `KOTLM_STATE_DIR` | `/var/lib/kotlm` | Directory for `auth.json` and `usage.json`; **must be writable** |
| `KOTLM_STATE_FILE`, `KOTLM_USAGE_FILE` | Derived from `STATE_DIR` | Exact paths when the state directory is unsuitable |
| `KOTLM_ALLOWED_MODELS` | `gpt-5.6-sol` | Comma-separated list of models |
| `KOTLM_UPSTREAM_TIMEOUT_SECONDS` | `180` | Provider request timeout |

## Metrics

`kotlm_requests_total{client,endpoint,outcome}`, `kotlm_tokens_total{client,kind}`, and `kotlm_upstream_errors_total{code}`, plus the standard Ktor and JVM metrics.

## Development

```bash
./kotlin test      # build and run tests
./kotlin package   # build/tasks/_kotlm_executableJarJvm/kotlm-jvm-executable.jar
./kotlin run
```

Tests do not access the network. The provider is replaced with a `MockEngine`, covering stream assembly, token rotation, limits, and the complete request path.

## License

Apache 2.0.
