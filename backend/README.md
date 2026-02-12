# CameraApp ML Backend

## Quick Start (Local Development)

### 1. Create virtual environment
```bash
cd backend
python -m venv venv
venv\Scripts\activate  # Windows
# source venv/bin/activate  # Linux/Mac
```

### 2. Install dependencies
```bash
pip install -r requirements.txt
```

### 3. Run the server
```bash
python server.py
```

Server will start at: `http://localhost:8000`

### 4. Test endpoints
- Health check: `GET http://localhost:8000/`
- Config: `GET http://localhost:8000/api/config`
- Detection: `POST http://localhost:8000/api/detect` (with image file)
- Classification: `POST http://localhost:8000/api/classify` (with image file)

## Docker Deployment

### Build image
```bash
docker build -t cameraapp-backend .
```

### Run container
```bash
docker run -p 8000:8000 cameraapp-backend
```

## API Documentation

Once running, visit `http://localhost:8000/docs` for interactive Swagger UI.

## Cloud Deployment

### Render.com
1. Push to GitHub
2. Connect repo in Render
3. Set build command: `pip install -r requirements.txt`
4. Set start command: `uvicorn server:app --host 0.0.0.0 --port $PORT`

### Railway.app
1. Push to GitHub  
2. Connect repo in Railway
3. Railway auto-detects Python and deploys

### Google Cloud Run
```bash
gcloud run deploy cameraapp-backend \
  --source . \
  --platform managed \
  --allow-unauthenticated
```
