# OpenTSx Visual Flow Builder 🎨
## No-Code Time Series Analysis Workbench

**A visual, interactive workbench for building time series analysis pipelines without code.**

---

## 🎯 Vision

Create a professional, intuitive web-based interface that enables researchers, analysts, and engineers to:

1. **Design** time series processing flows visually (like N8N, Apache NiFi, Node-RED)
2. **Visualize** real-time data in an oscilloscope-like interactive chart
3. **Configure** operators with live preview and state management
4. **Evaluate** each processing step on live data interactively
5. **Export** pipeline definitions as portable JSON descriptors
6. **Execute** multi-stream real-time TSA at scale

---

## 📐 System Architecture

### High-Level Components

```
┌─────────────────────────────────────────────────────────────────┐
│                    Web-Based Visual IDE                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│  │  Flow Canvas     │  │  Live Chart      │  │  Operator    │ │
│  │  (React Flow)    │  │  (Oscilloscope)  │  │  Library     │ │
│  │                  │  │  (Plotly/D3)     │  │              │ │
│  │  • Drag & Drop   │  │  • Real-time     │  │  • DFA       │ │
│  │  • Node Graph    │  │  • Multi-series  │  │  • MFDFA     │ │
│  │  • Connections   │  │  • Zoom/Pan      │  │  • Filters   │ │
│  │  • Auto-layout   │  │  • Cursors       │  │  • Transform │ │
│  └──────────────────┘  └──────────────────┘  └──────────────┘ │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│  │  Settings Panel  │  │  Data Inspector  │  │  Export      │ │
│  │                  │  │                  │  │              │ │
│  │  • Operator cfg  │  │  • Stats display │  │  • JSON      │ │
│  │  • Parameters    │  │  • Metadata      │  │  • YAML      │ │
│  │  • Validation    │  │  • Debug info    │  │  • Code gen  │ │
│  └──────────────────┘  └──────────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   Backend Execution Engine                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│  │  Flow Compiler   │→ │  Execution Plan  │→ │  Stream      │ │
│  │                  │  │                  │  │  Processor   │ │
│  │  • JSON → DAG    │  │  • Optimization  │  │              │ │
│  │  • Validation    │  │  • Parallelism   │  │  • Kafka In  │ │
│  │  • Type check    │  │  • Scheduling    │  │  • TSA Ops   │ │
│  └──────────────────┘  └──────────────────┘  │  • Kafka Out │ │
│                                               └──────────────┘ │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│  │  State Manager   │  │  Metrics         │  │  WebSocket   │ │
│  │                  │  │  Collector       │  │  Server      │ │
│  │  • Flow state    │  │                  │  │              │ │
│  │  • Operator cfg  │  │  • Performance   │  │  • Live data │ │
│  │  • Data cache    │  │  • Results       │  │  • Updates   │ │
│  └──────────────────┘  └──────────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎨 User Interface Design

### 1. Main Workbench Layout

```
┌────────────────────────────────────────────────────────────────────────┐
│  OpenTSx Visual Flow Builder                    [Save] [Run] [Export]  │
├──────────┬─────────────────────────────────────────────────┬───────────┤
│          │                                                 │           │
│ Operator │           Flow Canvas                           │   Live    │
│ Library  │                                                 │   Chart   │
│          │   ┌──────┐                                      │           │
│ 📊 Data  │   │Source│                                      │  ╱╲╱╲╱   │
│ • Kafka  │   └───┬──┘                                      │ ╱  ╲  ╲  │
│ • CSV    │       │                                         │╱    ╲  ╲ │
│ • Stream │       ↓                                         │      ╲   │
│          │   ┌──────┐      ┌──────┐                       │      Time │
│ 🔧 Prep  │   │Filter│  ──→ │ DFA  │                       │           │
│ • Normal │   └──────┘      └───┬──┘                       │  Series 1 │
│ • Detrend│                     ↓                          │  Series 2 │
│ • Resamp │                 ┌──────┐                       │           │
│          │                 │Vizual│                       │  [Play]   │
│ 📈 Algos │                 └──────┘                       │  [Pause]  │
│ • DFA    │                                                │  [Reset]  │
│ • MFDFA  │   [Settings: Filter] ────────────────────┐    │           │
│ • EventS │   • Window: 100                          │    │  Alpha:   │
│          │   • Type: Butterworth                    │    │  0.745    │
│ 💾 Output│   • Order: 4                             │    │           │
│ • Store  │   • Cutoff: 0.1 Hz                       │    │  R²:      │
│ • Alert  │   [Apply] [Cancel]                       │    │  0.982    │
│          │   └──────────────────────────────────────┘    │           │
└──────────┴─────────────────────────────────────────────────┴───────────┘
```

### 2. Oscilloscope-Like Chart Component

**Key Features:**
- **Real-time rendering** of time series data
- **Multi-channel display** (like oscilloscope)
- **Interactive cursors** for measurement
- **Zoom, pan, autoscale** controls
- **FFT/spectrum view** toggle
- **Statistical overlay** (mean, std, min, max)
- **Event markers** for detected patterns

**Technology Stack:**
- **Plotly.js** for interactive charts (primary)
- **uPlot** for ultra-fast real-time plotting (alternative)
- **D3.js** for custom visualizations

**Chart Modes:**
1. **Time Domain**: Classic oscilloscope view
2. **Frequency Domain**: FFT spectrum
3. **Phase Plot**: Embedding visualization
4. **Correlation**: Auto/cross-correlation
5. **Multifractal**: f(α) spectrum display

### 3. Flow Canvas (React Flow Implementation)

**Node Types:**

#### Data Source Nodes 🔵
- **Kafka Consumer**: Subscribe to topics
- **CSV File**: Upload or reference
- **Live Stream**: WebSocket connection
- **Generator**: Synthetic data (fBm, random walk)
- **Database**: Query historical data

#### Processing Nodes 🟢
- **Filter**: Butterworth, Gaussian, Median
- **Normalize**: Z-score, Min-max, Robust
- **Detrend**: Linear, Polynomial, Moving average
- **Resample**: Downsample, Upsample, Interpolate
- **Window**: Sliding, Tumbling, Session

#### Analysis Nodes 🟡
- **DFA**: Detrended Fluctuation Analysis
- **MFDFA**: Multifractal DFA
- **Event Sync**: Event Synchronization
- **RIS**: Return Interval Statistics
- **Granger**: Granger Causality
- **Peaks**: Peak detection
- **Trends**: Trend analysis

#### Output Nodes 🔴
- **Kafka Producer**: Send to topic
- **Database**: Store results
- **Webhook**: HTTP POST
- **Alert**: Condition-based alerts
- **Chart**: Visualize results
- **Export**: Save to file

**Node Configuration:**
```javascript
// Example node definition
{
  id: 'dfa_node_1',
  type: 'analysis.dfa',
  position: { x: 200, y: 100 },
  data: {
    label: 'DFA Analysis',
    config: {
      polynom_order: 1,
      min_scale: 10,
      max_scale: 1000,
      num_scales: 20,
      fit_range: [10, 500]
    },
    state: 'running', // idle, running, completed, error
    results: {
      alpha: 0.745,
      r_squared: 0.982,
      interpretation: 'Correlated (persistent)'
    }
  }
}
```

---

## 🔄 Workflow & User Experience

### Typical User Journey

**1. Start with Data Source**
```
User drags "Kafka Consumer" node onto canvas
  ↓
Configures topic, brokers, schema
  ↓
Clicks "Preview" to see live data in chart
```

**2. Add Processing Steps**
```
User drags "Normalize" node
  ↓
Connects Kafka → Normalize (visual edge)
  ↓
Chart updates to show normalized data
  ↓
User adjusts normalization method in settings
  ↓
Chart updates in real-time
```

**3. Apply Analysis**
```
User drags "DFA" node
  ↓
Connects Normalize → DFA
  ↓
DFA automatically runs on live data
  ↓
Results displayed in node badge (α=0.75)
  ↓
Chart switches to DFA log-log plot
```

**4. Export & Deploy**
```
User clicks "Export JSON"
  ↓
Pipeline descriptor downloaded
  ↓
User uploads to production system
  ↓
Execution engine runs pipeline on all streams
```

---

## 📋 JSON Descriptor Format

### Pipeline Definition Schema

```json
{
  "pipeline": {
    "id": "uuid-v4",
    "name": "Stock Market Persistence Analysis",
    "version": "1.0.0",
    "created": "2025-01-13T12:00:00Z",
    "author": "user@example.com",
    "description": "Analyze long-range correlation in stock prices",

    "metadata": {
      "tags": ["finance", "dfa", "stocks"],
      "category": "market-analysis",
      "schedule": "0 * * * *"
    },

    "nodes": [
      {
        "id": "source_1",
        "type": "data.kafka_consumer",
        "label": "Stock Prices",
        "position": {"x": 100, "y": 100},
        "config": {
          "bootstrap_servers": "localhost:9092",
          "topic": "stock_prices",
          "group_id": "dfa_analysis",
          "value_deserializer": "avro"
        }
      },
      {
        "id": "normalize_1",
        "type": "processing.normalize",
        "label": "Z-Score Normalization",
        "position": {"x": 300, "y": 100},
        "config": {
          "method": "zscore",
          "window": null
        }
      },
      {
        "id": "dfa_1",
        "type": "analysis.dfa",
        "label": "DFA Analysis",
        "position": {"x": 500, "y": 100},
        "config": {
          "polynom_order": 1,
          "min_scale": 10,
          "max_scale": 1000,
          "num_scales": 20,
          "fit_range": [10, 500]
        }
      },
      {
        "id": "output_1",
        "type": "output.kafka_producer",
        "label": "DFA Results",
        "position": {"x": 700, "y": 100},
        "config": {
          "bootstrap_servers": "localhost:9092",
          "topic": "dfa_results",
          "value_serializer": "avro"
        }
      }
    ],

    "edges": [
      {
        "id": "e1",
        "source": "source_1",
        "target": "normalize_1",
        "sourceHandle": "output",
        "targetHandle": "input"
      },
      {
        "id": "e2",
        "source": "normalize_1",
        "target": "dfa_1",
        "sourceHandle": "output",
        "targetHandle": "input"
      },
      {
        "id": "e3",
        "source": "dfa_1",
        "target": "output_1",
        "sourceHandle": "output",
        "targetHandle": "input"
      }
    ],

    "global_config": {
      "parallelism": 4,
      "checkpoint_interval_ms": 60000,
      "state_backend": "rocksdb",
      "error_handling": "retry_with_dlq"
    }
  }
}
```

### Node Type Definitions

```json
{
  "node_types": {
    "data.kafka_consumer": {
      "name": "Kafka Consumer",
      "category": "data_source",
      "icon": "📥",
      "color": "#3b82f6",
      "inputs": [],
      "outputs": ["time_series"],
      "config_schema": {
        "type": "object",
        "properties": {
          "bootstrap_servers": {
            "type": "string",
            "default": "localhost:9092"
          },
          "topic": {
            "type": "string",
            "required": true
          },
          "group_id": {
            "type": "string",
            "required": true
          }
        }
      }
    },

    "analysis.dfa": {
      "name": "DFA Analysis",
      "category": "analysis",
      "icon": "📊",
      "color": "#eab308",
      "inputs": ["time_series"],
      "outputs": ["dfa_results"],
      "config_schema": {
        "type": "object",
        "properties": {
          "polynom_order": {
            "type": "integer",
            "default": 1,
            "min": 1,
            "max": 5,
            "description": "Polynomial order for detrending"
          },
          "min_scale": {
            "type": "integer",
            "default": 10,
            "min": 4
          },
          "max_scale": {
            "type": "integer",
            "default": 1000
          },
          "num_scales": {
            "type": "integer",
            "default": 20,
            "min": 10,
            "max": 100
          }
        }
      },
      "output_schema": {
        "alpha": "number",
        "r_squared": "number",
        "interpretation": "string",
        "scales": "array<number>",
        "fluctuations": "array<number>"
      }
    }
  }
}
```

---

## ⚙️ State Management System

### State Architecture

**1. Frontend State (React + Zustand)**

```javascript
// Store definition
const useFlowStore = create((set, get) => ({
  // Flow state
  nodes: [],
  edges: [],

  // Execution state
  executionStatus: 'idle', // idle, running, paused, error
  liveData: {},

  // Settings state
  selectedNode: null,
  settingsOpen: false,

  // Actions
  addNode: (node) => set((state) => ({
    nodes: [...state.nodes, node]
  })),

  updateNodeConfig: (nodeId, config) => set((state) => ({
    nodes: state.nodes.map(n =>
      n.id === nodeId ? {...n, data: {...n.data, config}} : n
    )
  })),

  setLiveData: (nodeId, data) => set((state) => ({
    liveData: {...state.liveData, [nodeId]: data}
  }))
}));
```

**2. Backend State (Redis + PostgreSQL)**

```python
# State manager
class PipelineStateManager:
    def __init__(self, redis_client, db_session):
        self.redis = redis_client
        self.db = db_session

    def save_pipeline(self, pipeline: Pipeline):
        """Save pipeline definition to PostgreSQL"""
        self.db.add(pipeline)
        self.db.commit()

    def cache_node_state(self, node_id: str, state: dict):
        """Cache node execution state in Redis"""
        key = f"node_state:{node_id}"
        self.redis.setex(key, 3600, json.dumps(state))

    def get_node_state(self, node_id: str) -> dict:
        """Retrieve node state from cache"""
        key = f"node_state:{node_id}"
        data = self.redis.get(key)
        return json.loads(data) if data else {}

    def stream_results(self, node_id: str, result: dict):
        """Stream results via Redis pub/sub"""
        channel = f"results:{node_id}"
        self.redis.publish(channel, json.dumps(result))
```

**3. Persistence Strategy**

- **Volatile State (Redis)**:
  - Live data buffers
  - Execution metrics
  - Temporary results
  - WebSocket connections

- **Persistent State (PostgreSQL)**:
  - Pipeline definitions
  - User configurations
  - Historical results
  - Audit logs

---

## 🚀 Execution Engine

### Flow Compiler

Converts JSON descriptor into executable DAG:

```python
class FlowCompiler:
    """Compile visual flow to executable pipeline"""

    def compile(self, flow_json: dict) -> ExecutionPlan:
        """
        Convert JSON descriptor to optimized execution plan.

        Steps:
        1. Parse JSON and validate schema
        2. Build dependency graph (DAG)
        3. Topologically sort nodes
        4. Optimize (fusion, parallelization)
        5. Generate executable code
        """
        # Parse
        pipeline = Pipeline.from_json(flow_json)

        # Build DAG
        dag = self._build_dag(pipeline)

        # Validate (no cycles, type compatibility)
        self._validate_dag(dag)

        # Optimize
        optimized_dag = self._optimize_dag(dag)

        # Generate execution plan
        plan = self._generate_execution_plan(optimized_dag)

        return plan

    def _build_dag(self, pipeline: Pipeline) -> nx.DiGraph:
        """Build directed acyclic graph from pipeline"""
        G = nx.DiGraph()

        # Add nodes
        for node in pipeline.nodes:
            G.add_node(node.id, data=node)

        # Add edges
        for edge in pipeline.edges:
            G.add_edge(edge.source, edge.target, data=edge)

        return G

    def _validate_dag(self, dag: nx.DiGraph):
        """Validate DAG structure and types"""
        # Check for cycles
        if not nx.is_directed_acyclic_graph(dag):
            raise ValueError("Pipeline contains cycles!")

        # Check type compatibility
        for edge in dag.edges:
            source_type = self._get_output_type(dag.nodes[edge[0]])
            target_type = self._get_input_type(dag.nodes[edge[1]])

            if not self._types_compatible(source_type, target_type):
                raise TypeError(
                    f"Type mismatch: {edge[0]} outputs {source_type}, "
                    f"but {edge[1]} expects {target_type}"
                )

    def _optimize_dag(self, dag: nx.DiGraph) -> nx.DiGraph:
        """Apply optimization passes"""
        optimized = dag.copy()

        # Pass 1: Operator fusion
        optimized = self._fuse_compatible_operators(optimized)

        # Pass 2: Parallel execution opportunities
        optimized = self._identify_parallel_branches(optimized)

        # Pass 3: State caching
        optimized = self._add_caching_hints(optimized)

        return optimized

    def _generate_execution_plan(self, dag: nx.DiGraph) -> ExecutionPlan:
        """Generate executable plan"""
        # Topological sort for execution order
        execution_order = list(nx.topological_sort(dag))

        # Group into stages (parallel execution)
        stages = self._group_into_stages(dag, execution_order)

        # Create plan
        plan = ExecutionPlan(
            stages=stages,
            parallelism=self._determine_parallelism(dag),
            checkpointing=self._configure_checkpointing(dag)
        )

        return plan
```

### Stream Processor

Executes the compiled plan on live data:

```python
class StreamProcessor:
    """Execute pipeline on live data streams"""

    def __init__(self, execution_plan: ExecutionPlan):
        self.plan = execution_plan
        self.state_manager = StateManager()
        self.metrics_collector = MetricsCollector()

    async def run(self, input_streams: Dict[str, Stream]):
        """
        Run pipeline on input streams.

        Architecture:
        - Multi-threaded execution of parallel stages
        - Backpressure handling
        - Fault tolerance with checkpointing
        """
        try:
            # Initialize operators
            operators = self._initialize_operators()

            # Connect streams
            dataflow = self._connect_dataflow(input_streams, operators)

            # Start execution
            await self._execute_stages(dataflow)

        except Exception as e:
            self._handle_error(e)

    async def _execute_stages(self, dataflow: Dataflow):
        """Execute all stages with parallelism"""
        for stage in self.plan.stages:
            if stage.parallel:
                # Execute stage operators in parallel
                await asyncio.gather(*[
                    self._execute_operator(op, dataflow)
                    for op in stage.operators
                ])
            else:
                # Sequential execution
                for op in stage.operators:
                    await self._execute_operator(op, dataflow)

    async def _execute_operator(self, operator: Operator, dataflow: Dataflow):
        """Execute single operator on stream"""
        input_stream = dataflow.get_input(operator.id)

        async for data in input_stream:
            try:
                # Process
                result = await operator.process(data)

                # Emit to downstream
                dataflow.emit(operator.id, result)

                # Update metrics
                self.metrics_collector.record(operator.id, result)

                # Stream to UI via WebSocket
                await self._stream_to_ui(operator.id, result)

            except Exception as e:
                await self._handle_operator_error(operator, e)

    async def _stream_to_ui(self, operator_id: str, result: dict):
        """Stream results to UI in real-time"""
        message = {
            'type': 'operator_result',
            'operator_id': operator_id,
            'timestamp': time.time(),
            'data': result
        }

        await self.websocket_manager.broadcast(
            channel=f'pipeline:{self.plan.id}',
            message=message
        )
```

---

## 🎯 MVP Implementation Plan

### Phase 1: Core Infrastructure (2 weeks)

**Week 1: Frontend Foundation**
- [ ] Set up React + TypeScript + Vite project
- [ ] Integrate React Flow for canvas
- [ ] Create basic node types (Source, Process, Output)
- [ ] Implement drag-and-drop from operator library
- [ ] Basic node connection/edge creation
- [ ] Simple settings panel (modal)

**Week 2: Backend Foundation**
- [ ] FastAPI backend setup
- [ ] WebSocket server for live updates
- [ ] JSON descriptor schema definition
- [ ] Basic flow compiler (JSON → DAG)
- [ ] PostgreSQL schema for pipelines
- [ ] Redis setup for state caching

**Deliverables:**
- Working canvas with drag-and-drop
- Basic node connection
- Settings panel for node configuration
- JSON export/import working
- Backend API endpoints defined

---

### Phase 2: Live Data & Visualization (2 weeks)

**Week 3: Chart Component**
- [ ] Integrate Plotly.js for charts
- [ ] Real-time data streaming via WebSocket
- [ ] Multi-series display
- [ ] Zoom, pan, autoscale controls
- [ ] Statistical overlay (mean, std)
- [ ] Mode switching (time/frequency domain)

**Week 4: Live Execution**
- [ ] Simple Kafka consumer integration
- [ ] Data flow through pipeline nodes
- [ ] Real-time result updates
- [ ] Node status indicators (running, completed, error)
- [ ] Basic error handling

**Deliverables:**
- Live oscilloscope-like chart
- Real-time data flowing through nodes
- Visual feedback on execution status
- WebSocket communication working

---

### Phase 3: Operators & Analysis (2 weeks)

**Week 5: Core Operators**
- [ ] Implement DFA operator
- [ ] Implement Filter operator
- [ ] Implement Normalize operator
- [ ] Implement Detrend operator
- [ ] Each operator shows live preview
- [ ] Parameter validation

**Week 6: Advanced Features**
- [ ] MFDFA operator
- [ ] Event Synchronization operator
- [ ] Parallel branch execution
- [ ] Result caching
- [ ] Performance metrics display

**Deliverables:**
- 6+ working operators
- Live preview for each operator
- Parameter validation
- Performance monitoring

---

### Phase 4: Production Features (2 weeks)

**Week 7: Robustness**
- [ ] Error handling & recovery
- [ ] Pipeline validation
- [ ] Type checking
- [ ] State persistence (save/load)
- [ ] Undo/redo functionality
- [ ] Auto-save

**Week 8: Polish & Deploy**
- [ ] UI polish and responsive design
- [ ] Documentation
- [ ] Example pipelines library
- [ ] Docker deployment
- [ ] CI/CD pipeline
- [ ] User testing & feedback

**Deliverables:**
- Production-ready MVP
- Deployed to staging environment
- Documentation complete
- Example gallery

---

## 🛠️ Technology Stack

### Frontend

```json
{
  "framework": "React 18 + TypeScript",
  "state": "Zustand (lightweight, fast)",
  "canvas": "React Flow (visual flow editor)",
  "charts": "Plotly.js (interactive charts)",
  "ui": "Tailwind CSS + shadcn/ui",
  "build": "Vite (fast builds)",
  "websocket": "Socket.IO client"
}
```

### Backend

```json
{
  "framework": "FastAPI (Python 3.11+)",
  "async": "asyncio + uvicorn",
  "websocket": "Socket.IO server",
  "streaming": "Apache Kafka",
  "database": "PostgreSQL 15",
  "cache": "Redis 7",
  "validation": "Pydantic v2",
  "testing": "pytest + pytest-asyncio"
}
```

### Deployment

```yaml
services:
  frontend:
    image: node:18-alpine
    build: ./frontend
    ports:
      - "3000:3000"

  backend:
    image: python:3.11-slim
    build: ./backend
    ports:
      - "8000:8000"
    environment:
      - DATABASE_URL=postgresql://...
      - REDIS_URL=redis://...
      - KAFKA_BROKERS=kafka:9092

  postgres:
    image: postgres:15-alpine
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine

  kafka:
    image: confluentinc/cp-kafka:7.4.0
```

---

## 📊 Example Use Cases

### Use Case 1: Stock Market Analysis

**Pipeline:**
```
Kafka (stock_prices)
  → Normalize (z-score)
  → DFA (α detection)
  → Alert (if α > 0.7)
  → Kafka (alerts)
```

**Visual Flow:**
```
┌──────────┐     ┌──────────┐     ┌──────┐     ┌──────┐     ┌──────────┐
│  Kafka   │────→│Normalize │────→│ DFA  │────→│Alert │────→│  Kafka   │
│  Source  │     │ (Z-score)│     │ α=0.7│     │ >0.7 │     │  Output  │
└──────────┘     └──────────┘     └──────┘     └──────┘     └──────────┘
                                      ↓
                                  ┌──────┐
                                  │Chart │
                                  └──────┘
```

### Use Case 2: Climate Event Synchronization

**Pipeline:**
```
CSV (ENSO index) ──┐
                   ├─→ Event Sync → Chart
CSV (Rainfall)   ──┘
```

**Visual Flow:**
```
┌──────────┐
│  ENSO    │────┐
│  CSV     │    │
└──────────┘    │    ┌──────────┐     ┌──────┐
                ├───→│  Event   │────→│Chart │
┌──────────┐    │    │  Sync    │     └──────┘
│ Rainfall │────┘    └──────────┘
│  CSV     │
└──────────┘
```

### Use Case 3: Real-Time Anomaly Detection

**Pipeline:**
```
Kafka (sensor_data)
  → Filter (Butterworth)
  → Window (100 samples)
  → DFA (α tracking)
  → Anomaly Detector (α > 0.8 or α < 0.3)
  → Alert (SMS/Email)
  → Dashboard (Chart + Stats)
```

---

## 🎨 UI Component Specifications

### Node Component

```typescript
interface NodeProps {
  id: string;
  type: string;
  label: string;
  config: Record<string, any>;
  state: 'idle' | 'running' | 'completed' | 'error';
  results?: Record<string, any>;
}

const CustomNode: React.FC<NodeProps> = ({ data }) => {
  return (
    <div className="node-container">
      <div className="node-header">
        <span className="node-icon">{data.icon}</span>
        <span className="node-label">{data.label}</span>
        <StatusIndicator state={data.state} />
      </div>

      <Handle type="target" position="left" />

      <div className="node-body">
        {data.results && (
          <div className="node-results">
            {Object.entries(data.results).map(([key, value]) => (
              <div key={key} className="result-item">
                <span className="result-key">{key}:</span>
                <span className="result-value">{formatValue(value)}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <Handle type="source" position="right" />

      <button
        className="node-settings-btn"
        onClick={() => openSettings(data.id)}
      >
        ⚙️
      </button>
    </div>
  );
};
```

### Settings Panel Component

```typescript
interface SettingsPanelProps {
  node: NodeProps;
  onUpdate: (config: Record<string, any>) => void;
  onClose: () => void;
}

const SettingsPanel: React.FC<SettingsPanelProps> = ({ node, onUpdate, onClose }) => {
  const [config, setConfig] = useState(node.config);

  return (
    <div className="settings-panel">
      <div className="settings-header">
        <h3>Settings: {node.label}</h3>
        <button onClick={onClose}>✕</button>
      </div>

      <div className="settings-body">
        <FormBuilder
          schema={getNodeSchema(node.type)}
          values={config}
          onChange={setConfig}
        />
      </div>

      <div className="settings-footer">
        <button onClick={() => onUpdate(config)}>Apply</button>
        <button onClick={onClose}>Cancel</button>
      </div>
    </div>
  );
};
```

### Chart Component

```typescript
interface ChartProps {
  nodeId: string;
  liveData: TimeSeriesData[];
  mode: 'time' | 'frequency' | 'phase';
}

const OscilloscopeChart: React.FC<ChartProps> = ({ nodeId, liveData, mode }) => {
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Initialize Plotly chart
    const layout = {
      title: `Live Data: ${nodeId}`,
      xaxis: { title: 'Time' },
      yaxis: { title: 'Amplitude' },
      showlegend: true
    };

    Plotly.newPlot(chartRef.current, liveData, layout, {
      responsive: true,
      displayModeBar: true
    });
  }, []);

  useEffect(() => {
    // Update chart with live data
    Plotly.react(chartRef.current, liveData, layout);
  }, [liveData]);

  return (
    <div className="chart-container">
      <div className="chart-controls">
        <button onClick={() => setMode('time')}>Time</button>
        <button onClick={() => setMode('frequency')}>FFT</button>
        <button onClick={() => setMode('phase')}>Phase</button>
      </div>

      <div ref={chartRef} className="chart" />

      <div className="chart-stats">
        <StatDisplay label="Mean" value={calculateMean(liveData)} />
        <StatDisplay label="Std" value={calculateStd(liveData)} />
        <StatDisplay label="Min" value={calculateMin(liveData)} />
        <StatDisplay label="Max" value={calculateMax(liveData)} />
      </div>
    </div>
  );
};
```

---

## 🔐 Security Considerations

### Authentication & Authorization

- **JWT-based authentication** for API access
- **Role-based access control** (RBAC)
  - Admin: Full access
  - Editor: Create/edit/delete pipelines
  - Viewer: View-only access
- **API rate limiting** (100 req/min per user)
- **Input validation** on all endpoints

### Data Security

- **TLS/SSL** for all connections
- **Encrypted secrets** in pipeline configs
- **Audit logging** for all operations
- **Data isolation** per tenant/user

### Code Injection Prevention

- **Sandboxed execution** of user-defined code
- **Schema validation** for all JSON inputs
- **No eval()** or dynamic code execution
- **Whitelist** of allowed operators

---

## 📈 Performance Targets

### MVP Performance Goals

| Metric | Target |
|--------|--------|
| **UI Responsiveness** | < 100ms for all interactions |
| **Chart Update Rate** | 30 FPS (33ms per frame) |
| **WebSocket Latency** | < 50ms end-to-end |
| **Pipeline Compilation** | < 1s for 100-node graph |
| **Throughput** | 10,000 events/sec per pipeline |
| **Concurrent Users** | 100+ simultaneous users |
| **Max Pipeline Size** | 500 nodes |

### Optimization Strategies

1. **Virtual scrolling** for large node lists
2. **Canvas viewport culling** (only render visible nodes)
3. **WebSocket message batching**
4. **Server-side result caching**
5. **Lazy loading** of operator libraries
6. **Code splitting** for frontend bundles

---

## 🧪 Testing Strategy

### Frontend Testing

```typescript
// Component test
describe('CustomNode', () => {
  it('renders node with correct label', () => {
    render(<CustomNode data={mockNodeData} />);
    expect(screen.getByText('DFA Analysis')).toBeInTheDocument();
  });

  it('updates results when data changes', () => {
    const { rerender } = render(<CustomNode data={mockNodeData} />);
    rerender(<CustomNode data={{ ...mockNodeData, results: { alpha: 0.8 } }} />);
    expect(screen.getByText('0.8')).toBeInTheDocument();
  });
});

// Integration test
describe('Flow Canvas', () => {
  it('allows dragging node from library to canvas', async () => {
    render(<FlowCanvas />);
    const dfaNode = screen.getByText('DFA');

    await userEvent.drag(dfaNode, { clientX: 300, clientY: 200 });

    expect(screen.getByText('DFA Analysis')).toBeInTheDocument();
  });
});
```

### Backend Testing

```python
# Unit test
def test_flow_compiler_validates_dag():
    compiler = FlowCompiler()

    # Create cyclic flow (invalid)
    flow = {
        "nodes": [
            {"id": "a", "type": "source"},
            {"id": "b", "type": "process"}
        ],
        "edges": [
            {"source": "a", "target": "b"},
            {"source": "b", "target": "a"}  # Cycle!
        ]
    }

    with pytest.raises(ValueError, match="contains cycles"):
        compiler.compile(flow)

# Integration test
@pytest.mark.asyncio
async def test_stream_processor_executes_pipeline():
    # Create simple pipeline
    plan = create_test_pipeline()
    processor = StreamProcessor(plan)

    # Mock input stream
    mock_stream = create_mock_kafka_stream()

    # Execute
    results = []
    async for result in processor.run(mock_stream):
        results.append(result)
        if len(results) >= 10:
            break

    # Verify
    assert len(results) == 10
    assert all('alpha' in r for r in results)
```

---

## 📚 Documentation Plan

### User Documentation

1. **Getting Started Guide**
   - Installation
   - First pipeline tutorial
   - Operator reference

2. **Tutorial Series**
   - Building a DFA pipeline
   - Real-time anomaly detection
   - Multi-stream synchronization

3. **Operator Reference**
   - Each operator documented with:
     - Purpose & use cases
     - Configuration parameters
     - Input/output types
     - Example usage

4. **API Reference**
   - REST API endpoints
   - WebSocket events
   - JSON schema definitions

### Developer Documentation

1. **Architecture Guide**
   - System overview
   - Component interaction
   - Data flow diagrams

2. **Contributing Guide**
   - Adding new operators
   - Creating custom node types
   - Testing guidelines

3. **Deployment Guide**
   - Docker setup
   - Kubernetes manifests
   - Scaling considerations

---

## 🎯 Success Metrics

### MVP Success Criteria

- [ ] User can create a 5-node pipeline in < 5 minutes
- [ ] Live data flows through pipeline in real-time
- [ ] DFA analysis completes in < 2 seconds
- [ ] Chart updates at 30 FPS
- [ ] JSON export/import works flawlessly
- [ ] Zero critical bugs in production
- [ ] Positive feedback from 3+ pilot users

### Post-MVP Metrics

- **Adoption**: 50+ active users in first month
- **Engagement**: Average 3 pipelines created per user
- **Performance**: 99.9% uptime
- **Satisfaction**: NPS score > 40

---

## 🚀 Future Enhancements (Post-MVP)

### Phase 2 Features

1. **Collaborative Editing**
   - Multi-user real-time collaboration
   - Commenting on nodes
   - Version control (git-like)

2. **Advanced Visualizations**
   - 3D multifractal plots
   - Interactive f(α) surfaces
   - Custom chart builders

3. **ML Integration**
   - AutoML operator
   - Model training node
   - Prediction visualizations

4. **Marketplace**
   - Community-contributed operators
   - Pipeline templates
   - One-click deployment

5. **Advanced Execution**
   - GPU-accelerated operators
   - Distributed execution
   - Adaptive parallelism

---

## 📋 Implementation Checklist

### Sprint 1: Foundation (Week 1-2)
- [ ] Project setup (frontend + backend)
- [ ] React Flow integration
- [ ] Basic node types
- [ ] Settings panel
- [ ] JSON schema definition
- [ ] Database schema
- [ ] WebSocket server

### Sprint 2: Visualization (Week 3-4)
- [ ] Plotly chart component
- [ ] Real-time data streaming
- [ ] Multi-series display
- [ ] Chart controls
- [ ] Statistical overlays
- [ ] Mode switching

### Sprint 3: Operators (Week 5-6)
- [ ] DFA operator
- [ ] Filter operator
- [ ] Normalize operator
- [ ] Detrend operator
- [ ] MFDFA operator
- [ ] Event Sync operator

### Sprint 4: Production (Week 7-8)
- [ ] Error handling
- [ ] Pipeline validation
- [ ] State persistence
- [ ] Undo/redo
- [ ] Documentation
- [ ] Deployment
- [ ] User testing

---

## 🎓 Learning Resources

### For Developers

- **React Flow**: https://reactflow.dev/
- **Plotly.js**: https://plotly.com/javascript/
- **FastAPI**: https://fastapi.tiangolo.com/
- **Socket.IO**: https://socket.io/
- **Apache Kafka**: https://kafka.apache.org/

### For Users

- **Time Series Analysis**: MANUALS/01-PHYSICIST-JOURNEY.md
- **DFA Tutorial**: MANUALS/03-BEGINNERS-JOURNEY.md
- **Kafka Basics**: MANUALS/02-DEVELOPER-JOURNEY.md

---

## 🤝 Contributing

We welcome contributions! Areas where help is needed:

1. **New Operators**: Implement additional TSA algorithms
2. **Visualizations**: Create custom chart types
3. **Documentation**: Improve tutorials and guides
4. **Testing**: Add test coverage
5. **UI/UX**: Design improvements

---

## 📞 Support & Feedback

- **GitHub Issues**: https://github.com/kamir/OpenTSx/issues
- **Email**: support@opentsx.com
- **Discord**: (coming soon)

---

**Ready to revolutionize time series analysis with a visual, no-code interface!** 🚀

*Visual Flow Builder Design v1.0*
*Created: 2025-01-13*
*Compatible with: OpenTSx 3.0.0*
