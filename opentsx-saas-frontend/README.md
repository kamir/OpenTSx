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
├── public/
│   └── vite.svg
├── src/
│   ├── App.tsx                 # Main app with routing
│   ├── main.tsx                # Entry point
│   ├── index.css               # Global styles
│   ├── pages/
│   │   ├── Login.tsx           # Login page
│   │   ├── Register.tsx        # Registration page
│   │   ├── Dashboard.tsx       # Dashboard with flows list
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

#### Dashboard (`/dashboard`)
- Flows list with stats
- Create new flow button
- Organization overview
- Quick actions

#### Flow Builder (`/flow-builder` and `/flow-builder/:flowId`)
- Visual flow editor with React Flow
- Drag-and-drop nodes
- Node connections
- Save flow to backend

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
/dashboard            → Dashboard (protected)
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
FROM node:18-alpine

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

- **Full SaaS Documentation**: [../SAAS-PLATFORM.md](../SAAS-PLATFORM.md)
- **Visual Flow Builder Design**: [../WEB-UI-VISUAL-FLOW-BUILDER.md](../WEB-UI-VISUAL-FLOW-BUILDER.md)
- **API Reference**: [../docs/API-DOCUMENTATION.md](../docs/API-DOCUMENTATION.md)

## Demo Credentials

Default superuser account (created automatically):

- **Email**: `admin@opentsx.com`
- **Password**: `admin123`

⚠️ **Change this password in production!**

## License

Apache License 2.0
