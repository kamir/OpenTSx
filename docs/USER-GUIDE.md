# OpenTSx SaaS - Complete User Guide 📚

**Visual Time Series Analysis Platform**

Version 1.0.0 | Last Updated: January 2025

---

## Table of Contents

1. [Introduction](#introduction)
2. [Getting Started](#getting-started)
3. [User Authentication](#user-authentication)
4. [Organizations & Teams](#organizations--teams)
5. [Visual Flow Builder](#visual-flow-builder)
6. [Node Types Reference](#node-types-reference)
7. [Example Flows](#example-flows)
8. [Subscription Plans](#subscription-plans)
9. [API Integration](#api-integration)
10. [FAQ & Troubleshooting](#faq--troubleshooting)

---

## Introduction

### What is OpenTSx?

OpenTSx is a powerful, visual platform for time series analysis that makes advanced algorithms accessible through a no-code interface. Whether you're a researcher, data scientist, or analyst, OpenTSx enables you to:

- **Build** analysis pipelines visually with drag-and-drop
- **Analyze** time series data using cutting-edge algorithms (DFA, MFDFA, Event Synchronization)
- **Visualize** results in real-time with interactive charts
- **Collaborate** with teams and organizations
- **Deploy** pipelines to production with one click

### Key Features

✅ **No-Code Visual Builder** - Design pipelines like Zapier/N8N
✅ **Real-Time Analysis** - Process streaming data from Kafka
✅ **Advanced Algorithms** - DFA, MFDFA, Event Synchronization, and more
✅ **Team Collaboration** - Organizations, teams, and role-based access
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

### 2. Create Your First Organization

After signup, you'll be prompted to create an organization:

```
Organization Name: My Research Lab
Slug: my-research-lab (used in URLs)
```

**What's an Organization?**
An organization is a workspace where you and your team collaborate on flows. Each organization has:
- **Members**: Team members with different roles
- **Teams**: Sub-groups within the organization
- **Flows**: Shared analysis pipelines
- **Subscription**: Plan tier (Free, Starter, Professional, Enterprise)

### 3. Build Your First Flow

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

**Two-Factor Authentication** (Enterprise plan):
- Scan QR code with authenticator app
- Enter 6-digit code on each login

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

## Organizations & Teams

### Organization Management

#### View Organizations

Sidebar → "Organizations" shows all organizations you belong to:

```
┌─────────────────────────────────┐
│ My Research Lab          [Admin]│
│ 📊 5 flows · 3 members          │
│                                 │
│ Acme Corp               [Member]│
│ 📊 12 flows · 25 members        │
└─────────────────────────────────┘
```

#### Create Organization

1. Click "+ New Organization"
2. Fill in details:
   ```
   Name: Data Science Team
   Slug: data-science-team
   Description: Our analytics workspace
   ```
3. Click "Create"

Your new organization starts with **Free Plan**:
- 5 flows
- 1,000 executions/month
- 3 team members

#### Organization Settings

Click organization → "Settings":

- **General**: Name, description, logo
- **Members**: Manage team members
- **Teams**: Create sub-teams
- **Billing**: Upgrade plan, view usage
- **Danger Zone**: Delete organization

### Team Management

Teams are sub-groups within organizations for better organization.

#### Create Team

Organization → "Teams" → "+ New Team":

```
Name: Data Engineers
Description: Backend data pipeline team
```

#### Assign Members to Team

1. Go to Team page
2. Click "+ Add Member"
3. Select member from organization
4. Choose role (Admin, Member, Viewer)
5. Click "Add"

#### Team Permissions

- **Admin**: Full control over team flows
- **Member**: Create and edit team flows
- **Viewer**: View-only access

---

## Invitations

### Invite Team Members

#### 1. Send Invitation

Organization → "Members" → "+ Invite Member":

```
Email: colleague@company.com
Role: Member
Team: Data Engineers (optional)
```

Click "Send Invitation"

#### 2. Invitee Receives Email

```
Subject: You've been invited to join [Organization Name] on OpenTSx

Hi there!

[Your Name] has invited you to join [Organization Name] on OpenTSx.

[Accept Invitation Button]

This invitation expires in 7 days.
```

#### 3. Accept Invitation

Recipient clicks "Accept Invitation":
- If they have account: Added immediately
- If new user: Prompted to sign up first

### Manage Invitations

Organization → "Members" → "Pending Invitations":

- **View**: See all pending invitations
- **Resend**: Send reminder email
- **Revoke**: Cancel invitation

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

## Node Types Reference

### Data Source Nodes 📥

#### Kafka Consumer

Subscribe to Kafka topic for real-time data.

**Configuration**:
```yaml
Topic: stock_prices
Bootstrap Servers: localhost:9092
Group ID: dfa_analysis
Schema Registry: http://localhost:8081
```

**Output**: Stream of TimeSeriesObject

#### CSV File Upload

Load time series from CSV file.

**Configuration**:
```yaml
File: data.csv
Timestamp Column: date
Value Column: close
Parse Dates: true
```

**Output**: TimeSeriesObject

#### Data Generator

Generate synthetic time series for testing.

**Configuration**:
```yaml
Pattern: random_walk | fbm | sine | noise
Length: 1000
Hurst (for fBm): 0.7
```

**Output**: TimeSeriesObject

### Processing Nodes 🔧

#### Normalize

Normalize time series data.

**Configuration**:
```yaml
Method: zscore | minmax | robust
```

**Formulas**:
- **Z-Score**: (x - μ) / σ
- **Min-Max**: (x - min) / (max - min)
- **Robust**: (x - median) / IQR

#### Detrend

Remove polynomial trend.

**Configuration**:
```yaml
Order: 1  # 1=linear, 2=quadratic, etc.
```

#### Filter

Apply filters to remove noise.

**Configuration**:
```yaml
Type: butterworth | gaussian | median
Order: 4
Cutoff Frequency: 0.1 Hz
```

### Analysis Nodes 📊

#### DFA (Detrended Fluctuation Analysis)

Detect long-range correlations.

**Configuration**:
```yaml
Polynomial Order: 1
Min Scale: 10
Max Scale: 1000
Number of Scales: 20
```

**Output**:
```json
{
  "alpha": 0.745,
  "r_squared": 0.982,
  "interpretation": "Correlated (persistent)",
  "scales": [10, 15, 22, ...],
  "fluctuations": [0.5, 0.8, 1.2, ...]
}
```

**Interpretation**:
- α < 0.5: Anti-correlated (mean-reverting)
- α = 0.5: Uncorrelated (white noise)
- α > 0.5: Correlated (persistent)
- α ≈ 1.0: 1/f noise (pink noise)
- α > 1.0: Non-stationary

#### MFDFA (Multifractal DFA)

Analyze multifractal properties.

**Configuration**:
```yaml
Polynomial Order: 1
Q Range: [-10, 10]
```

**Output**:
```json
{
  "h_q": [0.8, 0.75, 0.7, ...],
  "delta_h": 0.15,
  "is_multifractal": true,
  "spectrum_width": 0.25
}
```

#### Event Synchronization

Detect synchronized events across multiple series.

**Configuration**:
```yaml
Threshold 1: 2.0 (standard deviations)
Threshold 2: 2.0
Max Time Lag: 30 (time units)
```

**Output**:
```json
{
  "overall_sync": 0.65,
  "sync_1_to_2": 0.45,
  "sync_2_to_1": 0.20,
  "leader": "Series 1"
}
```

### Output Nodes 📤

#### Kafka Producer

Send results to Kafka topic.

**Configuration**:
```yaml
Topic: dfa_results
Bootstrap Servers: localhost:9092
```

#### Chart Visualization

Visualize results in real-time.

**Modes**:
- Time Domain: Classic time series plot
- Frequency Domain: FFT spectrum
- DFA Plot: Log-log fluctuation plot
- Multifractal Spectrum: f(α) plot

#### Database Storage

Store results in database.

**Configuration**:
```yaml
Database: PostgreSQL | Cassandra
Table: analysis_results
```

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

## Subscription Plans

### Plan Comparison

| Feature | Free | Starter | Professional | Enterprise |
|---------|------|---------|--------------|------------|
| **Price** | $0/mo | $29/mo | $99/mo | Custom |
| **Flows** | 5 | 25 | 100 | Unlimited |
| **Executions/mo** | 1K | 50K | 500K | Unlimited |
| **Team Members** | 3 | 10 | 50 | Unlimited |
| **Real-time Streams** | 1 | 5 | 25 | Unlimited |
| **Support** | Community | Email | Priority | Dedicated |
| **Kafka Integration** | ✓ | ✓ | ✓ | ✓ |
| **API Access** | ✓ | ✓ | ✓ | ✓ |
| **Custom Operators** | - | - | ✓ | ✓ |
| **On-Premise Deploy** | - | - | - | ✓ |
| **SLA** | - | - | 99.9% | 99.99% |

### Upgrade Your Plan

1. Organization → "Billing"
2. Click "Upgrade Plan"
3. Select plan tier
4. Enter payment details
5. Confirm upgrade

**Proration**: Charged only for remaining month

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

#### Execute Flow

```bash
POST /api/v1/flows/1/execute
```

#### Get Execution Status

```bash
GET /api/v1/flows/1/executions/1
```

### Python SDK

```python
from opentsx_client import OpenTSxClient

# Initialize client
client = OpenTSxClient(
    api_key="your-api-key",
    base_url="https://api.opentsx.com"
)

# List flows
flows = client.flows.list(organization_id=1)

# Execute flow
execution = client.flows.execute(flow_id=1)

# Get results
results = client.executions.get(execution_id=execution.id)
print(f"Alpha: {results.node_results['dfa_1']['alpha']}")
```

---

## FAQ & Troubleshooting

### General Questions

**Q: Is OpenTSx free to use?**
A: Yes! The Free plan includes 5 flows and 1,000 executions per month.

**Q: Can I use OpenTSx for commercial projects?**
A: Yes, all plans (including Free) allow commercial use.

**Q: Do you offer academic discounts?**
A: Yes! Email support@opentsx.com with your .edu email for 50% off.

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

**Q: Invitation email not received**
A: Check:
- Spam/junk folder
- Email address is correct
- Invitation hasn't expired (7-day limit)

### Performance

**Q: Flow is slow**
A: Optimize:
- Reduce data size (resample, filter)
- Use fewer scales in DFA (20 instead of 50)
- Upgrade to higher plan for more resources

**Q: Real-time lag**
A: Tips:
- Check network latency to Kafka brokers
- Reduce processing complexity
- Use Professional plan for dedicated resources

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
