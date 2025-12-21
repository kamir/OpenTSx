#!/bin/bash

PORTS=(
  8000    # OpenTSx-SaaS 
  9092    # Kafka
  8080    # Kafka internal HTTP
  9094    # Schema Registry
  8070    # Schema Registry internal HTTP
  8081    # Tableflow internal HTTP
  9101    # Electron: (bacula-dir)
)

echo "Checking ports..."
echo "-----------------------------------"

for PORT in "${PORTS[@]}"; do
  echo -n "Port $PORT: "

  # Check if any process is listening
  if lsof -i TCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "❌ IN USE"

    # List the process
    echo "   → Process using $PORT:"
    lsof -i TCP:"$PORT" -sTCP:LISTEN

    # Offer kill suggestion
    PID=$(lsof -ti TCP:"$PORT" -sTCP:LISTEN)
    echo "   To free port $PORT:"
    echo "     kill -9 $PID"
  else
    echo "✔ FREE"
  fi

  echo ""
done

echo "-----------------------------------"
echo "Port check complete."
echo "-----------------------------------"
