# OpenTSx SaaS - Complete User Guide 📚

**Visual Time Series Analysis Platform**

Version 1.0.0 | Last Updated: January 2025

---

## Table of Contents

1. [Introduction](#introduction)
2. [Getting Started](#getting-started)
3. [User Authentication](#user-authentication)
4. [Visual Flow Builder](#visual-flow-builder)
5. [Example Flows](#example-flows)
6. [API Integration](#api-integration)
7. [FAQ & Troubleshooting](#faq--troubleshooting)

---

## Introduction

### What is OpenTSx?

OpenTSx is a powerful, visual platform for time series analysis that makes advanced algorithms accessible through a no-code interface. Whether you're a researcher, data scientist, or analyst, OpenTSx enables you to:

- **Build** analysis pipelines visually with drag-and-drop
- **Analyze** time series data using cutting-edge algorithms (DFA, MFDFA, Event Synchronization)
- **Visualize** results in real-time with interactive charts
- **Deploy** pipelines to production with one click

### Key Features

✅ **No-Code Visual Builder** - Design pipelines like Zapier/N8N
✅ **Real-Time Analysis** - Process streaming data from Kafka
✅ **Advanced Algorithms** - DFA, MFDFA, Event Synchronization, and more
✅ **Production-Ready** - Deploy to Kafka streams at scale
✅ **Interactive Charts** - Oscilloscope-like real-time visualization

---

## Getting Started

### 1. Create Your Account

Visit `https://app.opentsx.com/signup` and create a free account:

1. Enter your email address
2. Choose a strong password
3. Verify your email (check spam folder)
4. Complete your profile

🎉 Your account comes with a free organization and 5 flow slots!

### 2. Build Your First Flow

Let's create a simple DFA analysis flow:

**Step 1: Go to Flows**
- Click "Flows" in the sidebar
- Click "New Flow" button

**Step 2: Name Your Flow**
```
Name: My First DFA Analysis
Description: Analyze persistence in synthetic data
```

**Step 3: Add Nodes to Canvas**

1. **Add Data Source**:
   - Drag "Data Generator" from operator library
   - Configure: Pattern = Random Walk, Length = 1000
   - Click "Apply"

2. **Add DFA Analysis**:
   - Drag "DFA Analysis" node
   - Connect Generator → DFA
   - Configure: Polynomial Order = 1
   - Click "Apply"

3. **Add Visualization**:
   - Drag "Chart" node
   - Connect DFA → Chart

**Step 4: Execute**
- Click "Run" button
- Watch live data flow through pipeline
- See DFA results: α = 0.75 (persistent!)

🎊 Congratulations! You've created your first flow.

---

## User Authentication

### Login

Navigate to `https://app.opentsx.com/login`:

```
Email: your@email.com
Password: ********
```

### Password Reset

Forgot password?

1. Click "Forgot Password?" on login page
2. Enter your email
3. Check email for reset link
4. Create new password

### Profile Settings

Click your avatar → "Profile Settings":

- **Basic Info**: Name, email, bio
- **Avatar**: Upload profile picture
- **Preferences**: Theme (light/dark), notifications
- **API Keys**: Generate API keys for programmatic access
- **Sessions**: View active sessions, revoke access

---

## Visual Flow Builder

### Interface Overview

```
┌────────────────────────────────────────────────────────────┐
│  OpenTSx Flow Builder          [Save] [Run] [Export JSON]  │
├──────────┬───────────────────────────────────┬─────────────┤
│          │                                   │             │
│ Operator │     Flow Canvas                   │  Live Chart │
│ Library  │                                   │             │
│          │   ┌──────────┐                    │   ╱╲╱╲╱    │
│ 📊 Data  │   │Generator │                    │  ╱  ╲  ╲   │
│ • Kafka  │   └────┬─────┘                    │ ╱    ╲   ╲ │
│ • CSV    │        │                          │       Time  │
│ • Gen    │        ↓                          │             │
│          │   ┌────────┐      ┌─────────┐    │  Alpha:     │
│ 🔧 Proc  │   │Normalize│  ──→ │  DFA    │   │  0.745      │
│ • Filter │   └────────┘      └────┬────┘    │             │
│ • Normal │                        │         │  R²: 0.982  │
│          │                        ↓         │             │
│ 📈 Algo  │                   ┌────────┐     │  [Play]     │
│ • DFA    │                   │ Chart  │     │  [Pause]    │
│ • MFDFA  │                   └────────┘     │  [Reset]    │
└──────────┴───────────────────────────────────┴─────────────┘
```

### Building a Flow

#### 1. Add Nodes

**Drag & Drop**:
- Click and drag node from Operator Library
- Drop onto canvas
- Node appears at drop location

**Double-Click**:
- Double-click node type in library
- Node appears in center of canvas

#### 2. Connect Nodes

**Create Connection**:
1. Click output handle (right side) of source node
2. Drag to input handle (left side) of target node
3. Release to create edge

**Delete Connection**:
- Click edge to select
- Press Delete key

#### 3. Configure Nodes

**Open Settings**:
- Click node
- Settings panel opens on right

**Example: DFA Configuration**
```
Polynomial Order: 1          (1-5, linear detrending)
Min Scale: 10               (minimum window size)
Max Scale: 1000             (maximum window size)
Number of Scales: 20        (resolution of analysis)
Fit Range: [10, 500]        (range for alpha fitting)
```

**Apply Changes**:
- Click "Apply" to save
- Node updates immediately
- Live chart shows new results

#### 4. Execute Flow

**Run Once**:
- Click "Run" button
- Flow executes on current data
- See results in real-time

**Continuous Mode**:
- Click "Play" in chart panel
- Flow runs continuously on live data
- Perfect for Kafka streams

#### 5. Save & Export

**Save Flow**:
- Click "Save" button
- Flow stored in cloud
- Auto-save every 30 seconds

**Export JSON**:
- Click "Export JSON"
- Download pipeline descriptor
- Use in production or share with others

---

## Example Flows

### Example 1: Stock Market Persistence

**Goal**: Analyze long-range correlation in stock prices

**Flow**:
```
Kafka (stock_prices)
  → Normalize (z-score)
  → DFA (α detection)
  → Alert (if α > 0.7)
  → Kafka (alerts)
```

**Steps**:
1. Create "Stock Market DFA" flow
2. Add Kafka Consumer (topic: stock_prices)
3. Add Normalize node (method: zscore)
4. Connect Kafka → Normalize
5. Add DFA node (order: 1)
6. Connect Normalize → DFA
7. Add Alert node (condition: alpha > 0.7)
8. Connect DFA → Alert
9. Add Kafka Producer (topic: alerts)
10. Connect Alert → Kafka Producer

**Result**: Real-time persistence detection with alerts

### Example 2: Climate Event Synchronization

**Goal**: Detect synchronized events between ENSO and rainfall

**Flow**:
```
CSV (ENSO data) ──┐
                  ├→ Event Sync → Chart
CSV (Rainfall) ───┘
```

**Steps**:
1. Add two CSV Upload nodes
2. Upload enso.csv and rainfall.csv
3. Add Event Synchronization node
4. Connect both CSV nodes to Event Sync
5. Configure thresholds
6. Add Chart node
7. Run flow

**Result**: Visualize lead-lag relationship

### Example 3: Real-Time Anomaly Detection

**Goal**: Detect anomalies in sensor data

**Flow**:
```
Kafka (sensor_data)
  → Filter (noise removal)
  → Window (100 samples)
  → DFA (α tracking)
  → Anomaly Detector (α out of range)
  → Alert (SMS/Email)
```

---

## API Integration

### Authentication

All API requests require authentication via JWT token.

**Get Token**:
```bash
curl -X POST https://api.opentsx.com/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password"}'
```

**Response**:
```json
{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
  "token_type": "bearer"
}
```

**Use Token**:
```bash
curl https://api.opentsx.com/api/v1/flows \
  -H "Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGc..."
```

### Common Operations

#### List Flows

```bash
GET /api/v1/flows?organization_id=1
```

#### Create Flow

```bash
POST /api/v1/flows
Content-Type: application/json

{
  "name": "My Flow",
  "description": "Description",
  "definition": {
    "nodes": [...],
    "edges": [...]
  },
  "organization_id": 1
}
```

#### Get Execution Status

```bash
GET /api/v1/flows/1/executions/1
```

---

## FAQ & Troubleshooting

### Technical Issues

**Q: My flow won't execute**
A: Check:
- All nodes are connected properly
- Required configuration is filled in
- You haven't exceeded monthly execution limit
- Kafka brokers are accessible (if using Kafka nodes)

**Q: Chart shows no data**
A: Verify:
- Flow has executed successfully
- Chart node is connected to data source
- Data format is correct (TimeSeriesObject)

### Performance

**Q: Flow is slow**
A: Optimize:
- Reduce data size (resample, filter)
- Use fewer scales in DFA (20 instead of 50)

**Q: Real-time lag**
A: Tips:
- Check network latency to Kafka brokers
- Reduce processing complexity

### Support

**Email**: support@opentsx.com
**Community Forum**: https://community.opentsx.com
**Documentation**: https://docs.opentsx.com
**Status Page**: https://status.opentsx.com

---

## Appendix

### Glossary

- **DFA**: Detrended Fluctuation Analysis
- **MFDFA**: Multifractal Detrended Fluctuation Analysis
- **TSO**: TimeSeriesObject
- **Node**: Processing unit in flow
- **Edge**: Connection between nodes
- **Flow**: Complete analysis pipeline
- **Execution**: Single run of a flow

### Keyboard Shortcuts

- **Ctrl/Cmd + S**: Save flow
- **Ctrl/Cmd + Enter**: Execute flow
- **Delete**: Delete selected node/edge
- **Ctrl/Cmd + Z**: Undo
- **Ctrl/Cmd + Y**: Redo
- **Ctrl/Cmd + D**: Duplicate selected node
- **Space**: Pan canvas
- **Scroll**: Zoom canvas

### Resources

- **Python Package**: `pip install opentsx`
- **GitHub**: https://github.com/kamir/OpenTSx
- **Research Papers**: https://opentsx.com/research
- **Tutorial Videos**: https://youtube.com/opentsx

---

**Need Help?** Contact support@opentsx.com or join our community forum!

**Version**: 1.0.0
**Last Updated**: January 2025
**© 2025 OpenTSx Contributors**
