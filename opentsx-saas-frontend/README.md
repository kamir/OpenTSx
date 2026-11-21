# OpenTSx Frontend

React + TypeScript frontend with visual flow builder, authentication, and real-time data visualization.

## Read deployment refusals

The Workbench bounded and continuous Wayang targets display validated `opentsx.plan-refusal.v1` errors with their
fixed public message, original node and JSON-pointer field. Malformed refusal records show
`The deployment service returned an invalid located refusal.` Transport errors and private worker endpoints are
excluded from these records. Authentication and administrator-role checks remain on the backend.

The physical deployment profile is API-only. The target selector retains the existing local execution profiles;
physical execution requires independently observed host facts and the default provider refuses unavailable topic
incarnations. See [backend admission and offline upgrade](../opentsx-saas-backend/README.md#admit-physical-deployments)
and the [physical deployment contract](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s115-admit-a-physical-deployment).

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
├── public/
│   └── vite.svg
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
- **Plotly.js** - Interactive charts

## Key Features

### Authentication

- JWT-based authentication
- Token storage in localStorage
- Auto-redirect on 401 errors
- Protected routes

### Pages

#### Login Page (`/login`)
- Email/password authentication
- Demo account info display
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

Read the [analysis guide](../docs/devguide/workbench-analysis.md) for the result views, quality inspector, integrity-checked local snapshots and per-runtime deployment checks.

- The Swing bucket analyser in the browser; computes locally and needs no account
- Load a bucket: sample, CSV/TSV file, episode JSON, or episodes from the retrieval server
  (dev proxy `/retrieval`, target `OPENTSX_RETRIEVAL_URL`, default `http://localhost:8080`)
- TS Inventory: browse the series of a retrieval server (`GET /v1/inventory`) by tag, channel and scenario event,
  pick series, window and resolution; needs a retrieval server started with `--kafscale`, `--iceberg` or `--demo`
- Oscilloscope view: time base, vertical scale, cursors, trigger, measurements
- Ground truth: the scenario events of series loaded from the inventory are drawn as bands on the time axis
  (yellow: operation, red: sensor fault); the field "Ground truth" jumps to an event
- Align to vector: several nodes on one clock, one measuring point per tick (`opentsx.vector.v1`, a draft;
  `src/workbench/vector.ts`). Two series that give the same component of one vector, such as the wind speed of two
  turbines with key `all`, are refused with the message of the vector processor; choose a key that tells them apart.
  A stored flow whose `fill` is not `none` or `as-of` opens, but its preview fails with
  `vector fill must be none or as-of`. When the vector feeds a published node, the deploy checks list
  `<label>: vector fill must be none or as-of.`
- Pair windows (tab "Multi-variate Analysis"): a pair method compares two series window by window, paired by their
  episode ranges, one result per window at its centre; "Pair coverage" shows what the methods will see,
  "Cross-correlation" gives the peak of the lagged cross-correlation, its lag and link strength (`src/workbench/xcorr.ts`,
  ported from WikiExplorer.NG), "Event synchronisation" the share of synchronous events and the level of chance
  (`src/workbench/es.ts`, ported from the Python package). With clock 0 each pair range takes the larger sampling
  interval of its two episodes, as Wayang and the Rust runner do (shared cases:
  `../docs/datamodel-2026/testvectors/pair-clock.v1.json`). A `fill` other than `none` or `as-of` is refused with
  `pair fill must be none or as-of`. A series whose times are not in increasing order is refused with
  `<label> has times out of order. A pair method needs them in increasing order.`, where `<label>` is the label of
  the series. The vector processor does not run them. Catalog 1 lists `pair.xcorr` and `pair.es`, not `pair.coverage`;
  the DAG export marks every pair node `experimental: true` (`src/workbench/pairs.ts`, design in
  `../docs/datamodel-2026/PAIR-WINDOWS-SPEC.md`)
- Deploy and Stop (tab "Smart Topic"): a list says what a deployment still needs, "Create a vector of the loaded
  series" builds the one thing the processor runs, and Deploy hands the flow to the vector processor, which keeps the vector topic up to
  date, and shows what it reports; needs an administrator account, a retrieval server started with `--kafscale` and
  `--dag-runs true`, and a running vector processor. A separate list shows the Track A and B jobs of `GET /v1/dags`
  with job id, track, site, state, input record count and report age. It has no Stop: those jobs do not read the
  control topic
- Vector component names: the parameter `names` of "Align to vector" names the inputs from the left, comma
  separated. A typed name is written in lower-case snake case: lower case, every run of characters other than a-z
  and 0-9 becomes `_`, and `_` at either end is dropped. A typed name is never renamed, so two typed names with the
  same snake-case name are refused. A name left empty comes from the bucket name and gets `_2`, `_3` when another
  input has it. The preview of a vector with two inputs of one name fails with
  `two inputs of the vector are both named "<name>"; give every input its own name`. When the vector feeds a
  published node, the deploy checks list
  `<label>: two inputs of the vector are both named "<name>"; give every input its own name.`, so the flow is neither
  downloaded nor deployed. A vector that feeds no published node is left out of the DAG and does not block the flow
- Time series operations as buttons, recorded as a processing flow graph
- Export: `opentsx.dag.v1` Smart Topic definition, Processing Flow Descriptor, Java code. The DAG writes
  `# not in the stream operator catalog` after every operator that catalog 1
  (`../systems/catalogs/tsx-catalog-1.yaml`, list `CATALOG_1` in `src/workbench/ops.ts`) does not list
- Code in `src/workbench/`

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

You can run the entire stack (Frontend + Backend + DB) using the backend's docker-compose:

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

# Run tests with coverage
npm run test:coverage
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

### Serve the frontend image

`deploy/docker/saas-frontend.Dockerfile` serves the static build with nginx and [`nginx.conf`](nginx.conf) on port
8080. The image forwards `/api/` to the SaaS backend and `/retrieval/` to the retrieval server. Reading through
`/retrieval/` needs no account.

The image answers 403 to the write routes of the retrieval server: `/retrieval/v1/dags/` and
`/retrieval/v1/systems/<id>/generate`, and every path below either of them. `<id>` is the id of a system. Deploy,
Stop and generating a series go through the backend (`/api/v1/dags/`, `/api/v1/systems/<id>/generate`), which checks
the account. nginx matches the path after it decodes percent escapes, merges slashes and resolves `.` and `..`, so
`generate/`, `generate//` and `generate%2F` get 403 as well. The image is not the only way in: with `INGRESS=true`
the k3s Ingress sends `/v1` to the retrieval server directly, and there the run token is the only gate
([deploy/k3s](../deploy/k3s/README.md#vector-smart-topics)).

You need Python 3.12. Check the rules from the repository root:

```bash
python3 -m unittest discover -s tools -p 'test_saas_frontend_nginx.py' -v
```

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

For production, update `VITE_API_BASE_URL` to point to your production backend:

```bash
VITE_API_BASE_URL=https://api.opentsx.com
```

Then rebuild:

```bash
npm run build
```

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

- **User manual**: [../docs/user-manual/README.md](../docs/user-manual/README.md)
- **Developer guide**: [../docs/devguide/README.md](../docs/devguide/README.md)
- **API client**: [src/services/api.ts](src/services/api.ts)

## Demo Credentials

Default superuser account (created automatically):

- **Email**: `admin@opentsx.com`
- **Password**: `admin123`

⚠️ **Change this password in production!**

## License

Apache License 2.0
