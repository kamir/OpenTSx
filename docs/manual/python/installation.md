# Installation & Setup

This guide covers installing the OpenTSx Python package and setting up your environment for time series analysis.

## Prerequisites

### Required
- **Python 3.9+** (Python 3.9, 3.10, 3.11, or 3.12)
- **pip** (Python package manager)
- **virtualenv** or **venv** (recommended for isolated environments)

### Recommended
- **Jupyter** or **JupyterLab** for interactive analysis
- **Git** for source installation

## Installation Methods

### Method 1: Install from Source (Recommended)

This method gives you the latest features and allows you to modify the code.

```bash
# Clone the repository
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx/python-package

# Create virtual environment (recommended)
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install in development mode
pip install -e .

# Verify installation
python -c "from opentsx import TimeSeriesObject; print('✓ OpenTSx installed!')"
```

### Method 2: Install with Optional Dependencies

The package supports various optional feature sets:

```bash
# Install with Kafka support
pip install -e ".[kafka]"

# Install with visualization tools
pip install -e ".[viz]"

# Install with machine learning integration
pip install -e ".[ml]"

# Install with storage backends
pip install -e ".[storage]"

# Install with performance optimizations
pip install -e ".[performance]"

# Install everything
pip install -e ".[all]"
```

### Method 3: Minimal Installation (Core Only)

If you only need the core algorithms without extras:

```bash
cd OpenTSx/python-package
pip install numpy pandas scipy
pip install -e . --no-deps
```

## Verifying Installation

### Quick Verification

```bash
python3 verify_installation.py
```

Where `verify_installation.py` contains:

```python
#!/usr/bin/env python3
"""Verify OpenTSx Python installation."""

import sys

def verify_installation():
    print("Verifying OpenTSx installation...")
    print("-" * 60)

    # Test imports
    try:
        import numpy as np
        print("✓ NumPy imported successfully")
    except ImportError as e:
        print(f"✗ NumPy import failed: {e}")
        return False

    try:
        from opentsx import TimeSeriesObject
        print("✓ TimeSeriesObject imported")
    except ImportError as e:
        print(f"✗ TimeSeriesObject import failed: {e}")
        return False

    try:
        from opentsx.algorithms import DFA, MFDFA, EventSynchronization, RIS
        print("✓ All algorithms imported:")
        print("  - DFA (Detrended Fluctuation Analysis)")
        print("  - MFDFA (Multifractal DFA)")
        print("  - EventSynchronization")
        print("  - RIS (Return Interval Statistics)")
    except ImportError as e:
        print(f"✗ Algorithm import failed: {e}")
        return False

    # Test basic functionality
    try:
        ts = TimeSeriesObject(data=np.random.randn(100), label="test")
        print(f"\n✓ Created TimeSeriesObject: {ts}")

        dfa = DFA(polynom_order=1)
        results = dfa.analyze(ts)
        print(f"✓ DFA analysis: α={results['alpha']:.3f}")
    except Exception as e:
        print(f"✗ Functionality test failed: {e}")
        return False

    print("\n" + "="*60)
    print("✅ Installation successful!")
    print("="*60)
    print("\nNext steps:")
    print("  1. Try the examples in python-package/examples/")
    print("  2. Run the test suite: python test_implementations.py")
    print("  3. Follow the onboarding path: docs/onboarding/ONBOARDING-PATH-Python.md")

    return True

if __name__ == '__main__':
    success = verify_installation()
    sys.exit(0 if success else 1)
```

### Run Comprehensive Tests

```bash
cd python-package
python test_implementations.py
```

Expected output:
```
============================================================
OpenTSx Python Implementation Test Suite
============================================================
...
✅ ALL TESTS PASSED!
```

## Environment Setup

### Virtual Environment (Recommended)

Using a virtual environment isolates OpenTSx dependencies:

```bash
# Create virtual environment
python3 -m venv opentsx-env

# Activate
source opentsx-env/bin/activate  # Linux/macOS
# or
opentsx-env\Scripts\activate  # Windows

# Install OpenTSx
cd OpenTSx/python-package
pip install -e ".[all]"

# Deactivate when done
deactivate
```

### Jupyter Notebook Setup

```bash
# Install Jupyter
pip install jupyter notebook

# Install visualization tools
pip install matplotlib plotly

# Start Jupyter
jupyter notebook

# In notebook: Test import
from opentsx import TimeSeriesObject
from opentsx.algorithms import DFA, MFDFA
```

### JupyterLab Setup

```bash
# Install JupyterLab
pip install jupyterlab

# Launch
jupyter lab
```

## Dependency Management

### Core Dependencies

Automatically installed with the package:
- **numpy >= 1.24.0** - Numerical computing
- **pandas >= 2.0.0** - Data structures
- **scipy >= 1.10.0** - Scientific computing

### Optional Dependencies

#### Kafka Integration (`[kafka]`)
```bash
pip install -e ".[kafka]"
```
- confluent-kafka[avro] >= 2.3.0
- fastavro >= 1.8.0

#### Visualization (`[viz]`)
```bash
pip install -e ".[viz]"
```
- matplotlib >= 3.7.0
- plotly >= 5.17.0

#### Machine Learning (`[ml]`)
```bash
pip install -e ".[ml]"
```
- scikit-learn >= 1.3.0
- tensorflow >= 2.14.0

#### Storage Backends (`[storage]`)
```bash
pip install -e ".[storage]"
```
- cassandra-driver >= 3.28.0
- pyarrow >= 14.0.0
- tables >= 3.9.0 (HDF5)

#### Performance (`[performance]`)
```bash
pip install -e ".[performance]"
```
- numba >= 0.58.0 (JIT compilation)
- dask[complete] >= 2023.10.0 (parallel computing)

## Platform-Specific Notes

### macOS

May need to install compilers for scipy:
```bash
brew install gcc gfortran
pip install scipy
```

### Ubuntu/Debian

Install system dependencies:
```bash
sudo apt-get update
sudo apt-get install python3-dev gfortran libopenblas-dev
pip install scipy
```

### Windows

Use Anaconda for easier scipy installation:
```bash
conda install numpy pandas scipy matplotlib
pip install -e .
```

Or use pre-built wheels:
```bash
pip install numpy pandas scipy
pip install -e .
```

## Troubleshooting

### Import Errors

**Problem**: `ModuleNotFoundError: No module named 'opentsx'`

**Solution**:
```bash
# Ensure you're in the python-package directory
cd OpenTSx/python-package

# Install in editable mode
pip install -e .

# Verify python can find it
python -c "import opentsx; print(opentsx.__file__)"
```

### NumPy/SciPy Build Failures

**Problem**: scipy fails to build from source

**Solutions**:
```bash
# macOS
brew install gcc gfortran
pip install --upgrade pip setuptools wheel
pip install scipy

# Ubuntu/Debian
sudo apt-get install gfortran libopenblas-dev
pip install scipy

# All platforms: Use conda
conda install scipy
```

### Jupyter Kernel Issues

**Problem**: OpenTSx not found in Jupyter notebook

**Solution**:
```bash
# Install ipykernel in your virtual environment
pip install ipykernel

# Register the kernel
python -m ipykernel install --user --name=opentsx-env

# Select the kernel in Jupyter: Kernel > Change Kernel > opentsx-env
```

### Permission Errors

**Problem**: Permission denied during installation

**Solution**:
```bash
# Use --user flag
pip install --user -e .

# Or use virtual environment (recommended)
python3 -m venv venv
source venv/bin/activate
pip install -e .
```

## Upgrading

### Update from Git

```bash
cd OpenTSx
git pull origin main

cd python-package
pip install -e . --upgrade
```

### Reinstall Dependencies

```bash
pip install -e ".[all]" --upgrade
```

## Uninstalling

```bash
pip uninstall opentsx
```

## Next Steps

After successful installation:

1. **Quick Start**: Try the [Hello World example](README.md#quick-start)
2. **Learn the Basics**: Read [TimeSeriesObject in Python](timeseries-object.md)
3. **Run Examples**: Explore `/python-package/examples/`
4. **Follow Tutorial**: Complete the [Python Onboarding Path](../../onboarding/ONBOARDING-PATH-Python.md)

## Getting Help

If you encounter issues:

1. Check the [Troubleshooting Guide](../../TROUBLESHOOTING.md)
2. Review [GitHub Issues](https://github.com/kamir/OpenTSx/issues)
3. Open a new issue with:
   - Python version (`python --version`)
   - Operating system
   - Installation method
   - Full error message

---

**Ready?** [Continue to Python API Overview →](api-overview.md)
