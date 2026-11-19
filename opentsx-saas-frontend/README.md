# OpenTSx Frontend

React + TypeScript frontend with visual flow builder, authentication, and real-time data visualization.

## Quick Start

```bash
# Install dependencies
npm install

# Create .env file
cp .env.example .env
# Edit .env with backend URL

# Run development server
npm run dev
```

Frontend will be available at: **http://localhost:3000**

## Environment Variables

Create `.env` file in `frontend/` directory:

```bash
VITE_API_BASE_URL=http://localhost:8000
```

## Project Structure

```
frontend/
├── src/
│   ├── App.tsx                 # Main app with routing
│   ├── main.tsx                # Entry point
│   ├── index.css               # Global styles
│   ├── pages/
│   │   ├── Login.tsx           # Login page
│   │   ├── Register.tsx        # Registration page
│   │   ├── Dashboard.tsx       # OpenTSx Flow Composer: the flows of the account
│   │   └── FlowBuilder.tsx     # Visual flow builder
│   ├── services/
│   │   └── api.ts              # Axios API client
│   └── store/
│       └── authStore.ts        # Zustand auth state
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
├── tailwind.config.js
└── postcss.config.js
```

## Tech Stack

- **React 18** - UI library
- **TypeScript** - Type safety
- **Vite** - Build tool & dev server
- **React Router** - Client-side routing
- **Zustand** - Lightweight state management
- **Axios** - HTTP client
- **React Flow** - Visual flow builder
- **Tailwind CSS** - Utility-first CSS

## Key Features

### Authentication

- JWT-based authentication
- Token storage in localStorage
- Auto-redirect on 401 errors
- Protected routes
- Logout removes both tokens from this browser, also when the backend does not answer. The login page then says
  `signed out here; the server could not be told: <reason>`
- Only a 401 from `GET /api/v1/auth/me` ends the session. Another failure keeps it, and the Flow Composer, the flow
  builder and the systems page say `your account could not be read (<reason>); reload the page to try again`

### Pages

#### Login Page (`/login`)
- Email/password authentication
- The test operation hint when the backend runs with `TEST_LOGIN=true` (`GET /api/v1/auth/mode`). When that
  request fails, the page says `the sign-in mode could not be read from the backend (<reason>); reload the page to try again`
- Auto-redirect if already logged in

#### Register Page (`/register`)
- User registration form
- Email validation
- Password requirements

#### OpenTSx Flow Composer (`/dashboard`)
- Flows list with stats
- Create new flow button
- Organization overview
- Quick actions

#### Flow Builder (`/flow-builder` and `/flow-builder/:flowId`)
- Visual flow editor with React Flow
- Drag-and-drop nodes
- Node connections
- Save flow to backend

#### Workbench (`/workbench`)
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

### State Management

**Auth Store (Zustand):**

```typescript
interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;

  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  fetchCurrentUser: () => Promise<void>;
}
```

### API Client

Axios client with interceptors:

```typescript
// Automatically attach auth token
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Handle 401 errors
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('access_token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
```

## Routing

**Route Structure:**

```typescript
/                     → Redirect to /dashboard
/login                → Login page (public)
/register             → Register page (public)
/dashboard            → OpenTSx Flow Composer (protected)
/systems/:system/:place → Shell around a system: system, signals, flows, models (public except flows)
/flow-builder         → Create new flow (protected)
/flow-builder/:flowId → Edit existing flow (protected)
```

**Protected Routes:**

Routes that require authentication will automatically redirect to `/login` if user is not authenticated.

**Public Routes:**

Login and Register pages will automatically redirect to `/dashboard` if user is already authenticated.

## Development

```bash
# Install dependencies
npm install

# Run dev server with hot reload
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview

# Lint code
npm run lint
```

## Building for Production

```bash
# Build optimized bundle
npm run build

# Output in dist/ directory
ls dist/
```

The build creates optimized static files that can be served by any web server (Nginx, Apache, etc.).

## Docker

**Dockerfile:**

```dockerfile
FROM node:22-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .

CMD ["npm", "run", "dev", "--", "--host"]
```

**Build and Run:**

```bash
# Build
docker build -t opentsx-frontend .

# Run
docker run -p 3000:3000 opentsx-frontend
```

### Integrated Startup (Recommended)

You can run the entire stack (Frontend + Backend + DB) using the backend's docker-compose. Set `SECRET_KEY`,
`POSTGRES_PASSWORD` and `FIRST_SUPERUSER_PASSWORD` in `../opentsx-saas-backend/.env` first (see
[DOCKER-SETUP.md](../opentsx-saas-backend/DOCKER-SETUP.md)):

```bash
cd ../opentsx-saas-backend
docker-compose up -d --build
```

## Testing

```bash
# Install testing libraries
npm install -D @testing-library/react @testing-library/jest-dom @testing-library/user-event vitest

# Run tests
npm test
```

## Code Style

**Format with Prettier:**

```bash
# Install prettier
npm install -D prettier

# Format code
npx prettier --write src/
```

**Lint with ESLint:**

```bash
# Already configured with Vite

# Run linter
npm run lint
```

## Deployment

### Production Build

```bash
# Build
npm run build

# Serve with Nginx
sudo cp -r dist/* /var/www/html/opentsx/
```

### Nginx Configuration

```nginx
server {
    listen 80;
    server_name opentsx.com;
    root /var/www/html/opentsx;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api {
        proxy_pass http://localhost:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### Environment Variables in Production

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

## Component Examples

### Protected Route

```typescript
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading } = useAuthStore();

  if (isLoading) {
    return <div>Loading...</div>;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}
```

### API Call Example

```typescript
// Login
const handleLogin = async () => {
  try {
    await login({ email, password });
    navigate('/dashboard');
  } catch (error) {
    console.error('Login failed:', error);
  }
};

// Fetch flows
const loadFlows = async () => {
  const flows = await flowsAPI.list();
  setFlows(flows);
};
```

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

```bash
# Kill process on port 3000
lsof -ti:3000 | xargs kill -9

# Or use different port
npm run dev -- --port 3001
```

### CORS Errors

Ensure backend CORS is configured to allow `http://localhost:3000`:

```python
# In backend main.py
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

### Build Errors

```bash
# Clear cache
rm -rf node_modules package-lock.json
npm install

# Clear Vite cache
rm -rf node_modules/.vite
```

### TypeScript Errors

```bash
# Check TypeScript configuration
npx tsc --noEmit

# Update TypeScript
npm install -D typescript@latest
```

## Browser Support

- Chrome/Edge: Last 2 versions
- Firefox: Last 2 versions
- Safari: Last 2 versions

## Performance

- Code splitting enabled via Vite
- Lazy loading for routes
- Asset optimization in production build
- Tree-shaking to remove unused code

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
