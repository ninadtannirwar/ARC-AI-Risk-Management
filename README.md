# ARC — Adaptive Risk Core
### 🚀 AI-Powered Solana Trading Engine  
**🏆 Hacksagon 2026 · App Development Track | 👑 Team Pheonex**

---

## 📌 Project Overview
**ARC (Adaptive Risk Core)** is a high-performance, AI-driven trading ecosystem designed to navigate the volatile Solana meme coin market. By combining a distributed Python backend with a modern Android dashboard, ARC provides traders with institutional-grade tools: real-time risk scoring, automated trade execution, and deep AI-powered insights.

Built for the **Hacksagon 2026 Hackathon**, ARC focuses on safety and transparency, filtering out 90%+ of "rug-pulls" before they even hit your wallet.

---

## 🏗️ Architecture: The 3-Tier Pipeline
ARC evaluates every token through a rigorous three-stage validation engine:

1.  **Tier-1: Quantitative Math (The Filter)**  
    Scans for liquidity depth, bonding curve progress, and developer "burn" signatures.
2.  **Tier-2: Market Analysis (The Audit)**  
    Analyzes holder distribution (anti-whale), social momentum, and buy/sell pressure.
3.  **Tier-3: Gemini AI (The Brain)**  
    Leverages Google Gemini 1.5 Flash to perform sentiment analysis and detect malicious contract logic hidden in plain sight.

---

## ✨ Key Features

### 📱 Android Dashboard (Frontend)
- **Live Portfolio Tracking**: Real-time SOL balance and PnL monitoring.
- **Trading Insights**: Dynamic calculation of Win Rate, AI Confidence, and Open Trades.
- **Logbook**: Real-time WebSocket stream showing exactly what the AI is thinking (Snipe/Reject/Rug alerts).
- **Practice Mode**: A full paper-trading environment with a starting **10 SOL** virtual balance to test strategies safely.
- **Seamless Sync**: Automatic background synchronization between the Python backend CSV and local Room Database.

### ⚙️ Singularity Engine (Backend)
- **FastAPI Core**: High-concurrency RESTful API handling trade execution and state management.
- **WebSocket Broadcaster**: Real-time log broadcasting to all connected mobile clients.
- **CsvStore**: Secure logging of every trade, rejection, and AI score for historical analysis.
- **Security First**: Mandatory `X-ARC-Key` authentication for all REST communication.

---

## 🛠️ Tech Stack
- **Android**: Kotlin, Jetpack Compose, Hilt (DI), Room (Local Storage), Retrofit (Networking), Coroutines/Flow.
- **Backend**: Python 3.10+, FastAPI, Uvicorn, HTTPX (Async), CSV Data Storage.
- **AI**: Google Gemini API.
- **Blockchain**: Solana Web3.py.

---

## 🚀 Getting Started

### 1. Backend Setup
```bash
cd backend
pip install -r requirements.txt
cp .env.example .env   # Configure your API keys here
python main.py
```
*The backend defaults to port `8001`.*

### 2. Android Setup
1. Open the project in **Android Studio (Ladybug or newer)**.
2. Ensure you have **JDK 17** configured.
3. Build and run on a physical device or emulator (API 33+).
4. **Connection**: On the app's Dashboard, tap the cloud icon ☁️ and enter your server's IP (e.g., `10.244.49.239:8001`).

### 3. API Authentication
All REST requests must include the following header:
`X-ARC-Key: arc-pheonex-2026`

---

## 📊 API Documentation

| Method | Endpoint | Description |
|:---:|:---|:---|
| `POST` | `/start` | Launches the Singularity trading engine. |
| `POST` | `/stop` | Activates the kill-switch (closes positions & stops engine). |
| `GET` | `/stats` | Returns real-time PnL, AI calls, and wallet balance. |
| `GET` | `/trades` | Fetches historical trade data for local DB sync. |
| `POST` | `/config` | Toggles between `LIVE` and `SIMULATION` (Paper) mode. |
| `WS` | `/ws/logs` | Real-time WebSocket stream of engine logs. |

---

## 👑 Team Pheonex (Hacksagon 2026)
- **Lead Developer**: [Your Name/GitHub]
- **Track**: App Development
- **Goal**: To make automated Solana trading safer and more accessible through AI.

---
*Disclaimer: ARC is an experimental trading tool. Cryptocurrency trading involves significant risk.*
