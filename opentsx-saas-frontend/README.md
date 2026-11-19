# OpenTSx Frontend

The React and TypeScript web application of OpenTSx: sign-in, the Flow Composer, the Systems pages, the web
workbench and the visual flow builder. This page takes you from a checkout to a development server and a static
build. It was checked with Node 22 and the package versions in `package-lock.json`.

## Run the development server

You need Node 22 with npm, and the SaaS backend on `http://localhost:8000`
([backend README](../opentsx-saas-backend/README.md)).

```bash
npm ci
cp .env.example .env
npm run dev
```

`npm ci` installs the versions that `package-lock.json` pins. The page is at `http://localhost:3000`. The dev server
([`vite.config.ts`](vite.config.ts)) forwards `/api` to `http://localhost:8000` and `/retrieval` to
`OPENTSX_RETRIEVAL_URL` (default `http://localhost:8080`). `.env` sets the address of the backend, see
[Set the backend address](#set-the-backend-address).

## Find the code

| Path | What it holds |
|------|---------------|
| [`src/App.tsx`](src/App.tsx) | the routes, see [Routes](#routes) |
| [`src/main.tsx`](src/main.tsx) | the entry point and the page `This page failed: <message>` |
| [`src/pages/`](src/pages) | one component per page |
| [`src/services/api.ts`](src/services/api.ts) | the clients of the backend and the retrieval server, and how an error answer is read |
| [`src/store/authStore.ts`](src/store/authStore.ts) | the sign-in state |
| [`src/workbench/`](src/workbench) | the web workbench and the Flow Composer |
| [`src/systems/`](src/systems) | the places of the Systems page |
| [`nginx.conf`](nginx.conf) | the nginx site of the static image |
| [`Dockerfile`](Dockerfile) | the image of the development server for Docker Compose |

## Tech Stack

| Package | What it does here |
|---------|-------------------|
| React 18 (`react`, `react-dom`) | the pages |
| TypeScript (`typescript`) | type checks, `npx tsc --noEmit` |
| Vite (`vite`) | the development server and the build |
| React Router (`react-router-dom`) | the routes in `src/App.tsx` |
| Zustand (`zustand`) | the sign-in state in `src/store/authStore.ts` and the flow of the workbench in `src/workbench/flow.ts` |
| Axios (`axios`) | the requests to the backend in `src/services/api.ts` |
| React Flow (`reactflow`) | the canvas of the flow builder, the processing flow graph of the workbench and the plan picture of the Systems page |
| Tailwind CSS (`tailwindcss`) | the classes of the Login, Register and Flow Builder pages, of the loading screen in `src/App.tsx` and of `This page failed` |
| Vitest (`vitest`) | the tests, `npx vitest run` |

## Sign in

- Sign-in (`POST /api/v1/auth/login`) keeps the access token as `access_token` in `localStorage` of the browser.
  Every request to the backend carries it as `Authorization: Bearer <token>`.
- A 401 from the backend removes `access_token` and `refresh_token` and opens `/login`. On `/workbench` and `/login`
  the page stays: the workbench keeps its flow in memory and says `your session has expired, sign in again`. A 401
  of `/auth/login` or `/auth/register` is a refused sign-in, not an expired session: the form shows the reason. Both interceptors are in [`src/services/api.ts`](src/services/api.ts).
- `/dashboard` and `/flow-builder` need a token and open `/login` without one. `/login` and `/register` open
  `/dashboard` when the browser holds a token.
- Logout removes both tokens from this browser, also when the backend does not answer. The login page then says
  `signed out here; the server could not be told: <reason>`
- When `GET /api/v1/auth/me` fails with another status than 401, the session stays, and the Flow Composer, the flow
  builder and the systems page say `your account could not be read (<reason>); reload the page to try again`

## Pages

### Login (`/login`)
- Email and password
- The test operation hint when the backend runs with `TEST_LOGIN=true` (`GET /api/v1/auth/mode`). When that
  request fails, the page says `the sign-in mode could not be read from the backend (<reason>); reload the page to try again`
- Opens `/dashboard` after the sign-in

### Register (`/register`)
- Full name, email address and a password of at least 8 characters
- Signs in with the new account and opens `/dashboard`

### OpenTSx Flow Composer (`/dashboard`)
- Lists the flows of the account and how many of them run. The filters are the domain, "Flows with a Smart Topic",
  "Flows built on inventory series" and a text filter on name, track, operator and topic
- "New flow" opens the workbench with an empty flow; "Open in the editor" opens the selected flows in the workbench,
  the first in this tab and each other one in a tab of its own. When the browser keeps one of those tabs from opening,
  the page stays, opens none of them here and says `The browser kept <n> of the flows from opening in tabs of their own, so this page stayed and did not open the first one either. Allow pop-ups for this page, or open them one at a time.`
  The place Flows of the Systems page does the same; its notice goes when another place or system opens
- Changes the name and description of a flow, copies and deletes the selected flows
- Starts and stops the Smart Topic of a flow on the vector processor
- Links to the Systems page, the workbench and the flow builder

### Flow Builder (`/flow-builder`)
- A React Flow canvas that starts with three nodes: Data Generator, DFA Analysis and Chart
- Drag nodes and connect them
- "Save Flow" stores a new flow of the account (`POST /api/v1/flows`) and opens the Flow Composer
- It cannot open a stored flow. The workbench cannot open its flows either: the Flow Composer lists them as
  `flow builder`

### Systems (`/systems`)
- Reads the systems of the retrieval server (`GET /v1/systems`) and opens the first one at `/systems/<system>/signals`
- The places System, Signals, Flows, Models and Simulate of a system; see
  [chapter 14 of the user manual](../docs/user-manual/14-saas-service-layer.md)
- Needs no account; the place Flows lists flows only after the sign-in

### Workbench (`/workbench`)
- The Swing bucket analyser in the browser; computes locally and needs no account
- Load a bucket: sample, CSV/TSV file, episode JSON, or episodes from the retrieval server
  (dev proxy `/retrieval`, target `OPENTSX_RETRIEVAL_URL`, default `http://localhost:8080`)
- TS Inventory: browse the series of a retrieval server (`GET /v1/inventory`) by tag, channel and scenario event,
  pick series, window and resolution; needs a retrieval server started with `--kafscale`, `--iceberg` or `--source demo`
- Oscilloscope view: time base, vertical scale, cursors, trigger, measurements
- Ground truth: the scenario events of series loaded from the inventory are drawn as bands on the time axis
  (yellow: operation, red: sensor fault); the field "Ground truth" jumps to an event
- Align to vector: several nodes on one clock, one measuring point per tick (`opentsx.vector.v1`, a draft;
  `src/workbench/vector.ts`)
- Pair windows (tab "Multi-variate Analysis"): a pair method compares two series window by window, paired by their
  episode ranges, one result per window at its centre; "Pair coverage" shows what the methods will see,
  "Cross-correlation" gives the peak of the lagged cross-correlation, its lag and link strength (`src/workbench/xcorr.ts`,
  ported from WikiExplorer.NG), "Event synchronisation" the share of synchronous events and the level of chance
  (`src/workbench/es.ts`, ported from the Python package). Workbench only, not in the stream operator catalog (`src/workbench/pairs.ts`, design in
  `../EVOLUTION/TASK-015-multivariate-analysis.md`)
- Deploy and Stop (tab "Smart Topic"): a list says what a deployment still needs, "Create a vector of the loaded
  series" builds the one thing the processor runs, and Deploy hands the flow to the vector processor, which keeps the vector topic up to
  date, and shows what it reports; needs an administrator account, a retrieval server started with `--kafscale` and
  `--dag-runs true`, and a running vector processor. Deploy, Start (Flow Composer) and "Publish and start" read
  `GET /v1/dags` first and refuse a flow whose topic another deployment writes. While that list is read or cannot be
  read, Deploy stays off and the panel says why. Deploy, Start and "Publish and start" fail with
  `the deployments could not be read from <url> (<reason>); try again` when the list cannot be read. Every node a
  flow publishes is the clean signal or an "Align to vector" node: any other published node keeps the flow from
  deploying, and the list names it, because the processor writes only the clean series and vector topics.
- Time series operations as buttons, recorded as a processing flow graph
- Export: `opentsx.dag.v1` Smart Topic definition, Processing Flow Descriptor, Java code
- Code in `src/workbench/`; limits in `../EVOLUTION/TASK-013-web-workbench.md`

## Routes

The routes are in [`src/App.tsx`](src/App.tsx).

| Path | Page | Account |
|------|------|---------|
| `/` | opens `/dashboard` | |
| `/login` | Login | opens `/dashboard` when the browser holds a token |
| `/register` | Register | opens `/dashboard` when the browser holds a token |
| `/dashboard` | OpenTSx Flow Composer | needed; opens `/login` without a token |
| `/flow-builder` | Flow Builder | needed; opens `/login` without a token |
| `/workbench` | Workbench | not needed |
| `/systems` | Systems, the first system | not needed |
| `/systems/<system>/<place>` | Systems, one place of one system | not needed |
| any other path | opens `/dashboard` | |

## Check a change

The CI job `SaaS frontend` (`.github/workflows/ci.yml`) runs these commands after `npm ci`:

```bash
npx tsc --noEmit
npm run lint
npx vitest run
npm run build
```

`npm test` runs vitest in watch mode. `npm run build` writes the static files to `dist/`. `npm run preview` serves
`dist/` at `http://localhost:4173`.

## Run in Docker

[`Dockerfile`](Dockerfile) runs the development server (`npm run dev`) on port 3000 in `node:22.14.0-alpine3.21`
and installs with `npm ci`.

The static image is [`deploy/docker/saas-frontend.Dockerfile`](../deploy/docker/saas-frontend.Dockerfile): it runs
`npm run build` and serves `dist/` with nginx and [`nginx.conf`](nginx.conf) on port 8080.
`deploy/k3s/10-build.sh` builds it for k3s.

### Run frontend, backend and PostgreSQL with Docker Compose

[`../opentsx-saas-backend/docker-compose.yml`](../opentsx-saas-backend/docker-compose.yml) builds this `Dockerfile` as the
service `frontend` and starts it next to the backend and PostgreSQL. Set `SECRET_KEY`,
`POSTGRES_PASSWORD` and `FIRST_SUPERUSER_PASSWORD` in `../opentsx-saas-backend/.env` first (see
[DOCKER-SETUP.md](../opentsx-saas-backend/DOCKER-SETUP.md)):

```bash
cd ../opentsx-saas-backend
docker compose up -d --build
```

## Serve the static build with nginx

[`nginx.conf`](nginx.conf) is the site of the static image:

| Location | What nginx does |
|----------|-----------------|
| `/assets/` | serves the hashed build output with `Cache-Control: public, max-age=31536000, immutable` |
| `/retrieval/v1/dags/` | answers 403 `{"error":"deploying and stopping DAGs goes through /api/v1/dags/","type":"forbidden"}` |
| `/retrieval/v1/systems/<id>/generate` | answers 403 `{"error":"generating a signal goes through /api/v1/systems/","type":"forbidden"}` |
| `/retrieval/` | forwards to `http://opentsx-retrieval:8080/`, waits 60 s |
| `/studio/` | forwards to `http://opentsx-studio:80/`, waits 60 s; `/studio` opens `/studio/` |
| `/api/v1/dags/`, `/api/v1/systems/` | forward to `http://opentsx-saas-backend:8000`, wait 300 s |
| `/api/` | forwards to `http://opentsx-saas-backend:8000`, waits 60 s |
| `/` | serves `index.html` for every path without a file, with `Cache-Control: no-cache` |

nginx answers a 502 or 504 of `/api/` and `/retrieval/` with a JSON error body; see
[Read error messages](#read-error-messages). nginx does not start when one of the host names
`opentsx-retrieval`, `opentsx-studio` or `opentsx-saas-backend` cannot be resolved. Reading the retrieval server
needs no account: expose the image only on a trusted network.

To serve the build from another nginx, copy `nginx.conf` and replace `opentsx-retrieval:8080`,
`opentsx-studio:80` and `opentsx-saas-backend:8000` with `<host>:<port>` of each service, where `<host>` is the host
name and `<port>` the port that service listens on.

## Set the backend address

`VITE_API_BASE_URL` is the address of the SaaS backend as the browser sees it, without `/api/v1`. Vite reads it
at build time from the environment or from `.env`. `.env` is git-ignored; `.env.example` is the template.

| `VITE_API_BASE_URL` | The page calls |
|---------------------|----------------|
| empty | its own origin; nginx (`nginx.conf`) and the dev server (`vite.config.ts`) forward `/api` to the backend |
| unset | its own origin after `npm run build`, `http://localhost:8000` under `npm run dev` |
| a URL | that URL; the backend must allow the origin of the page (CORS) |

The image `deploy/docker/saas-frontend.Dockerfile` builds with an empty value. To build for a backend on another
host, set the URL and rebuild. `<backend-host>` is the host name of the SaaS backend:

```bash
VITE_API_BASE_URL=https://<backend-host> npm run build
```

The retrieval server has no build variable: the page reaches it at `/retrieval` of its own origin. nginx
(`nginx.conf`) forwards it to `opentsx-retrieval:8080`, the dev server to `OPENTSX_RETRIEVAL_URL` (default
`http://localhost:8080`).

## Read error messages

Every page shows the reason a server gives. `errorMessage` in `src/services/api.ts` reads it from an answer of the
backend (axios), and `fetchJson` in the same file reads it from an answer of the retrieval server (fetch).

| The answer | The page shows |
|------------|----------------|
| `{"error": "<text>", "type": "<type>"}` from the backend, the retrieval server, the event bridge or nginx | `<text>` |
| 401 from the backend for any request but sign-in and register | `your session has expired, sign in again` |
| another error answer without such a body, for example the HTML page of a proxy | `HTTP <status> from <url>` |
| no answer | `the backend at <url> cannot be reached` |

A sign-in refused with 401 shows the `error` of the backend, for example `email or password: not correct; check both and sign in again`.
A register refused with 403, 409 or 422 shows it too, for example `sign-up is closed; ask an administrator of this installation for an account`.

nginx (`nginx.conf`) answers a 502 or 504 that it gives itself as an error body:

| Path | Status | Body | Fix |
|------|--------|------|-----|
| `/api/` | 502 | `{"error": "the backend is not reachable", "type": "unavailable"}` | Start the Service `opentsx-saas-backend` on port 8000. |
| `/api/` | 504 | `{"error": "the backend did not answer in time", "type": "unavailable"}` | Read the log of `opentsx-saas-backend`. The request may still finish there. nginx waits 300 s on `/api/v1/dags/` and `/api/v1/systems/`, 60 s on the rest of `/api/`. |
| `/retrieval/` | 502 | `{"error": "the retrieval server is not reachable", "type": "unavailable"}` | Start the Service `opentsx-retrieval` on port 8080. |
| `/retrieval/` | 504 | `{"error": "the retrieval server did not answer in time", "type": "unavailable"}` | Read the log of `opentsx-retrieval`. nginx waits 60 s. |

A page that fails to render shows `This page failed: <message>` and a Reload button.

A part of a page that cannot be read leaves the rest usable, and the page says what is missing:

| Where | Message |
|-------|---------|
| Systems page, workbench opening a signal | `your working copy could not be read (<reason>); showing the system document.` |
| Systems page | `the decisions could not be read (<reason>).` |
| Systems page | `the plan with your working copy could not be read (<reason>); showing the plan of the system document.` |
| Simulate | `The runs kept by the server could not be read: <reason>.` |
| Simulate | `The run <run> could not be shown: <reason>.` |
| Publish as another signal | `The other signals of <system> could not be read: <reason>.` |
| Deploy panel, Flow Composer, Publish | `Whether this account may deploy could not be read: <reason>.` |
| Stop in the deploy panel | `<dag> is stopped. The status of flow <id> stays as it was: <reason>.` |
| Flow Composer | `Start, Stop and Delete stay off: the deployments could not be read from <url> (<reason>); try again.` |
| Flow Composer | `<n> flows in your account, how many run is not known.`, on a system `<k> of the <n> flows in your account belong to this system, how many run is not known.` |

The Flow Composer keeps Start, Stop and Delete off until `GET /v1/dags` of the retrieval server answers. Until then it does not count the running flows, and the guide of the Systems page shows the Run step without them.

## Troubleshooting

### Port Already in Use

When port 3000 is taken, `npm run dev` prints `Port 3000 is in use, trying another one...` and serves the page on
the next free port; the line `Local:` names it. Show the process that holds port 3000:

```bash
lsof -i :3000
```

Stop that process only if it is yours. To choose the port of the dev server yourself:

```bash
npm run dev -- --port 3001
```

### CORS Errors

The backend accepts requests from another origin only when `BACKEND_CORS_ORIGINS` lists it; the
[backend README](../opentsx-saas-backend/README.md#environment-variables) gives its default. With an empty
`VITE_API_BASE_URL` the page calls its own origin and needs no entry there.

### Build Errors

```bash
rm -rf node_modules
npm ci
```

### TypeScript Errors

```bash
npx tsc --noEmit
```

`npm ci` installs the TypeScript version that `package-lock.json` pins.

## Documentation

- **Backend**: [../opentsx-saas-backend/README.md](../opentsx-saas-backend/README.md)
- **API reference**: the running backend serves it at `/api/v1/openapi.json` and `/docs`
  ([backend README, Documentation](../opentsx-saas-backend/README.md#documentation))
- **Docker Compose for backend, frontend and PostgreSQL**:
  [../opentsx-saas-backend/DOCKER-SETUP.md](../opentsx-saas-backend/DOCKER-SETUP.md)
- **Web workbench and Systems shell**: [chapter 14 of the user manual](../docs/user-manual/14-saas-service-layer.md)

## Administrator Account

The backend creates the first administrator at start when no administrator exists, with the address `FIRST_SUPERUSER_EMAIL` and the
password `FIRST_SUPERUSER_PASSWORD`. There is no default password: the backend does not start without one and
refuses a value shorter than 12 characters.

## License

Apache License 2.0
