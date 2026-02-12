# CameraApp

A dual-component system featuring an Android app for real-time computer vision and a Python-based ML backend for remote inference.

## 🚀 Overview

CameraApp is designed for high-performance object detection and classification. It leverages on-device processing for speed and cloud-based inference for more complex models, providing a seamless AI-powered camera experience.

## ✨ Key Features

- **Real-time Object Detection**: Integrated YOLOv8 for fast and accurate object tracking.
- **Image Classification**: EfficientNet-based classification for high-precision labeling.
- **Oriented Bounding Boxes (OBB)**: Support for detecting rotated objects.
- **Dual Inference Modes**: Choose between on-device TFLite processing and remote FastAPI-based inference.
- **Model Distribution (OTA)**: Backend support for downloading and updating ML models over-the-air.
- **Dockerized Backend**: Easily deploy the inference server anywhere.

## 🛠️ Tech Stack

### Android Application
- **Language**: Kotlin
- **Camera**: CameraX API
- **ML Engine**: TensorFlow Lite (TFLite)
- **UI Framework**: Jetpack Compose / Android XML
- **Architecture**: MVVM with Clean Architecture principles

### ML Backend (FastAPI)
- **Language**: Python 3.11
- **API Framework**: FastAPI
- **ML Library**: TensorFlow, NumPy, PIL
- **Deployment**: Docker, Uvicorn

## 📥 Setup & Installation

### Android Application
1.  Open the project in **Android Studio** (Hedgehog or later recommended).
2.  Sync Gradle projects.
3.  Ensure you have an Android device or emulator running API 26 (Android 8.0) or higher.
4.  Build and run the `app` module.

### ML Backend

#### Local Setup (Python)
1.  Navigate to the `backend` directory:
    ```bash
    cd backend
    ```
2.  Create a virtual environment and activate it:
    ```bash
    python -m venv venv
    source venv/bin/activate  # On Windows: venv\\Scripts\\activate
    ```
3.  Install dependencies:
    ```bash
    pip install -r requirements.txt
    ```
4.  Run the server:
    ```bash
    python server.py
    ```
    The API will be available at `http://localhost:8000`.

#### Docker Setup
1.  Build the Docker image:
    ```bash
    docker build -t camera-app-backend ./backend
    ```
2.  Run the container:
    ```bash
    docker run -p 8000:8000 camera-app-backend
    ```

## 📜 API Documentation

Once the backend is running, you can access the interactive Swagger UI at:
`http://localhost:8000/docs`

## 🤝 Contributing

1.  Fork the repository.
2.  Create your feature branch (`git checkout -b feature/AmazingFeature`).
3.  Commit your changes (`git commit -m 'Add some AmazingFeature'`).
4.  Push to the branch (`git push origin feature/AmazingFeature`).
5.  Open a Pull Request.
