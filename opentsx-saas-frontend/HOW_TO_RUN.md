# How to Run OpenTSx SaaS Frontend

## ✅ Setup Complete

The frontend has been renamed to **opentsx-saas-frontend** and is ready to run!

### What Was Done:

- ✅ Renamed from `frontend` to `opentsx-saas-frontend`
- ✅ Fixed TypeScript compilation errors
- ✅ Installed all dependencies (545 packages)
- ✅ Created `.env` file from template
- ✅ Added Vite environment type definitions
- ✅ Updated package.json with correct name
- ✅ Verified build works successfully

---

## Quick Start (3 Steps)

### 1. Navigate to Frontend Directory

```bash
cd /home/user/OpenTSx/opentsx-saas-frontend
```

### 2. Start Development Server

```bash
npm run dev
```

### 3. Open in Browser

The frontend will be available at: **http://localhost:5173/**

*(Vite's default port is 5173, but you can specify a different port - see below)*

---

## Running on Specific Port

### Port 3000 (as mentioned in README):

```bash
npm run dev -- --port 3000
```

Then open: **http://localhost:3000/**

### Port 8080:

```bash
npm run dev -- --port 8080
```

---

## Available Commands

### Development

```bash
# Start dev server with hot reload
npm run dev

# Start on specific port
npm run dev -- --port 3000

# Start and expose to network
npm run dev -- --host
```

### Building

```bash
# Build for production
npm run build

# Output will be in dist/ directory
```

### Preview

```bash
# Preview production build locally
npm run preview

# Preview on specific port
npm run preview -- --port 4173
```

---

## Configuration

### Environment Variables

The `.env` file is already created with default values:

```bash
VITE_API_BASE_URL=http://localhost:8000
```

**To connect to backend:**

1. Ensure backend is running on port 8000
2. Or update `VITE_API_BASE_URL` in `.env` to match your backend URL

### Backend Connection

The frontend expects the backend API to be available at:

- **API Base URL**: `http://localhost:8000`
- **API Endpoints**: `http://localhost:8000/api/v1/...`

**Start the backend first:**

```bash
cd /home/user/OpenTSx/opentsx-saas-backend
python -m uvicorn app.main:app --reload --port 8000
```

---

## Project Structure

```
opentsx-saas-frontend/
├── src/
│   ├── App.tsx                 # Main app with routing
│   ├── main.tsx                # Entry point
│   ├── index.css               # Global styles (Tailwind)
│   ├── vite-env.d.ts          # Vite environment types
│   ├── pages/
│   │   ├── Login.tsx          # Login page
│   │   ├── Register.tsx       # Registration page
│   │   ├── Dashboard.tsx      # Dashboard (flows list)
│   │   └── FlowBuilder.tsx    # Visual flow builder
│   ├── services/
│   │   └── api.ts             # API client (Axios)
│   └── store/
│       └── authStore.ts       # Auth state (Zustand)
├── public/                     # Static assets
├── dist/                       # Build output (after npm run build)
├── .env                        # Environment variables
├── package.json               # Dependencies & scripts
├── vite.config.ts             # Vite configuration
├── tailwind.config.js         # Tailwind CSS config
├── tsconfig.json              # TypeScript config
└── README.md                  # Documentation
```

---

## Tech Stack

- **React 18** - UI library
- **TypeScript 5** - Type safety
- **Vite 5** - Build tool & dev server (fast HMR)
- **React Router 6** - Client-side routing
- **Zustand** - State management
- **Axios** - HTTP client
- **React Flow 11** - Visual flow builder
- **Tailwind CSS 3** - Utility-first CSS
- **Plotly.js** - Interactive charts

---

## Features

### 🔐 Authentication

- JWT-based authentication
- Login & registration pages
- Token storage in localStorage
- Auto-redirect on 401 errors
- Protected routes

### 📊 Dashboard

- View all analysis flows
- Create new flows
- Quick stats (total flows, active flows, plan)
- Flow status indicators

### 🎨 Visual Flow Builder

- Drag-and-drop node editor
- Connect nodes to create pipelines
- Save flows to backend
- Real-time updates

### 🔄 Pages & Routes

```
/                     → Redirect to /dashboard
/login                → Login page (public)
/register             → Register page (public)
/dashboard            → Dashboard (protected)
/flow-builder         → Create new flow (protected)
/flow-builder/:flowId → Edit existing flow (protected)
```

---

## Demo Credentials

When backend is running with demo data:

- **Email**: `admin@opentsx.com`
- **Password**: `admin123`

⚠️ **Change password in production!**

---

## Development Workflow

### 1. Make Changes

Edit files in `src/` directory. Vite will automatically reload the browser.

### 2. Check TypeScript

```bash
# Type check without building
npx tsc --noEmit
```

### 3. Build & Test

```bash
# Build for production
npm run build

# Preview the build
npm run preview
```

---

## Troubleshooting

### Port Already in Use

```bash
# Kill process on port 3000
lsof -ti:3000 | xargs kill -9

# Or use different port
npm run dev -- --port 3001
```

### CORS Errors

Ensure backend has CORS configured for frontend URL:

```python
# In backend app/main.py
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

### Dependencies Issues

```bash
# Clear and reinstall
rm -rf node_modules package-lock.json
npm install
```

### Build Errors

```bash
# Clear Vite cache
rm -rf node_modules/.vite

# Rebuild
npm run build
```

### TypeScript Errors

```bash
# Check for type errors
npx tsc --noEmit

# Fix common issues
npm install -D @types/node
```

---

## Production Deployment

### 1. Build

```bash
npm run build
```

### 2. Output

Build artifacts will be in `dist/` directory:

```
dist/
├── index.html
├── assets/
│   ├── index-[hash].js
│   └── index-[hash].css
└── vite.svg
```

### 3. Serve

**Option A: Nginx**

```nginx
server {
    listen 80;
    server_name opentsx.com;
    root /var/www/opentsx-frontend/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api {
        proxy_pass http://localhost:8000;
        proxy_set_header Host $host;
    }
}
```

**Option B: Docker**

```dockerfile
FROM nginx:alpine
COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**Option C: Static Hosting**

Upload `dist/` to:
- Vercel
- Netlify
- AWS S3 + CloudFront
- GitHub Pages

---

## Performance

- ⚡ Lightning-fast HMR with Vite
- 📦 Code splitting enabled
- 🌳 Tree-shaking to remove unused code
- 🗜️ Asset optimization in production
- 💾 Lazy loading for routes

---

## Testing the Setup

### 1. Start Backend

```bash
cd /home/user/OpenTSx/opentsx-saas-backend
python -m uvicorn app.main:app --reload --port 8000
```

### 2. Start Frontend

```bash
cd /home/user/OpenTSx/opentsx-saas-frontend
npm run dev
```

### 3. Open Browser

Navigate to: **http://localhost:5173/**

### 4. Login

- Email: `admin@opentsx.com`
- Password: `admin123`

### 5. Explore

- View dashboard
- Create a flow
- Open flow builder
- Save flow

---

## Next Steps

1. **Start Backend**: See `opentsx-saas-backend/HOW_TO_RUN.md`
2. **Customize**: Update branding, colors, features
3. **Add Nodes**: Implement more flow node types
4. **Tests**: Add unit & integration tests
5. **Deploy**: Build and deploy to production

---

## Documentation

- **Full README**: [README.md](./README.md)
- **SaaS Platform Guide**: [../docs/devguide/guides/SAAS-PLATFORM.md](../docs/devguide/guides/SAAS-PLATFORM.md)
- **Visual Flow Builder**: [../docs/devguide/guides/WEB-UI-VISUAL-FLOW-BUILDER.md](../docs/devguide/guides/WEB-UI-VISUAL-FLOW-BUILDER.md)

---

## Support

For issues:

1. Check `README.md`
2. Review error logs in browser console
3. Check backend logs
4. Verify CORS configuration
5. Open issue at: https://github.com/kamir/OpenTSx/issues

---

**Last Updated**: 2025-12-21
**Status**: ✅ Ready to run
**Node.js**: v22.21.1
**npm**: v10.9.4
**Vite**: v5.4.21
