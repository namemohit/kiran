"""
CameraApp Backend Server
FastAPI server for YOLOv8 and EfficientNet ML inference.
"""

import io
import logging
import time
from typing import List, Dict, Any, Optional
from pathlib import Path

import numpy as np
from PIL import Image
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Initialize FastAPI app
app = FastAPI(
    title="CameraApp ML Backend",
    description="Backend API for YOLOv8 object detection and EfficientNet classification",
    version="1.0.0"
)

# Add CORS middleware for mobile app access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Model paths
MODELS_DIR = Path(__file__).parent / "models"
YOLO_MODEL_PATH = MODELS_DIR / "yolov8n.tflite"
EFFICIENTNET_MODEL_PATH = MODELS_DIR / "efficientnet_lite0.tflite"
COCO_LABELS_PATH = MODELS_DIR / "coco_labels.txt"
IMAGENET_LABELS_PATH = MODELS_DIR / "imagenet_labels.txt"

# Global model instances
yolo_interpreter = None
efficientnet_interpreter = None
coco_labels = []
imagenet_labels = []


# Response models
class Detection(BaseModel):
    label: str
    confidence: float
    bbox: List[float]  # [x1, y1, x2, y2] normalized 0-1


class Classification(BaseModel):
    label: str
    confidence: float


class DetectionResponse(BaseModel):
    success: bool
    detections: List[Detection]
    inference_time_ms: float


class ClassificationResponse(BaseModel):
    success: bool
    classifications: List[Classification]
    inference_time_ms: float


class ConfigResponse(BaseModel):
    app_version: str
    min_confidence_detection: float
    min_confidence_classification: float
    models_available: List[str]
    features: Dict[str, bool]


def load_labels(path: Path) -> List[str]:
    """Load labels from text file."""
    if not path.exists():
        logger.warning(f"Labels file not found: {path}")
        return []
    with open(path, 'r') as f:
        return [line.strip() for line in f.readlines()]


def load_models():
    """Load TFLite models at startup."""
    global yolo_interpreter, efficientnet_interpreter, coco_labels, imagenet_labels
    
    try:
        import tensorflow as tf
        
        # Load YOLOv8 model
        if YOLO_MODEL_PATH.exists():
            yolo_interpreter = tf.lite.Interpreter(model_path=str(YOLO_MODEL_PATH))
            yolo_interpreter.allocate_tensors()
            logger.info(f"Loaded YOLOv8 model from {YOLO_MODEL_PATH}")
        else:
            logger.warning(f"YOLOv8 model not found at {YOLO_MODEL_PATH}")
        
        # Load EfficientNet model
        if EFFICIENTNET_MODEL_PATH.exists():
            efficientnet_interpreter = tf.lite.Interpreter(model_path=str(EFFICIENTNET_MODEL_PATH))
            efficientnet_interpreter.allocate_tensors()
            logger.info(f"Loaded EfficientNet model from {EFFICIENTNET_MODEL_PATH}")
        else:
            logger.warning(f"EfficientNet model not found at {EFFICIENTNET_MODEL_PATH}")
        
        # Load labels
        coco_labels = load_labels(COCO_LABELS_PATH)
        imagenet_labels = load_labels(IMAGENET_LABELS_PATH)
        
        logger.info(f"Loaded {len(coco_labels)} COCO labels, {len(imagenet_labels)} ImageNet labels")
        
    except Exception as e:
        logger.error(f"Error loading models: {e}")


@app.on_event("startup")
async def startup_event():
    """Load models when server starts."""
    logger.info("Starting CameraApp ML Backend...")
    load_models()
    logger.info("Server ready!")


@app.get("/")
async def root():
    """Health check endpoint."""
    return {"status": "ok", "message": "CameraApp ML Backend is running"}


@app.get("/api/config", response_model=ConfigResponse)
async def get_config():
    """Get remote app configuration."""
    models = []
    if yolo_interpreter:
        models.append("yolov8n")
    if efficientnet_interpreter:
        models.append("efficientnet_lite0")
    
    return ConfigResponse(
        app_version="2.0.0",
        min_confidence_detection=0.25,
        min_confidence_classification=0.1,
        models_available=models,
        features={
            "object_detection": yolo_interpreter is not None,
            "image_classification": efficientnet_interpreter is not None,
            "ai_log": True,
            "export": True
        }
    )


# Model versioning for OTA updates
MODEL_VERSIONS = {
    "yolov8n.tflite": "1.0.0",
    "efficientnet_lite0.tflite": "1.0.0"
}


class ModelInfoResponse(BaseModel):
    yolo_version: str
    efficientnet_version: str


@app.get("/api/models/info", response_model=ModelInfoResponse)
async def get_model_info():
    """Get available model versions for OTA updates."""
    return ModelInfoResponse(
        yolo_version=MODEL_VERSIONS.get("yolov8n.tflite", "1.0.0"),
        efficientnet_version=MODEL_VERSIONS.get("efficientnet_lite0.tflite", "1.0.0")
    )


from fastapi.responses import FileResponse

@app.get("/api/models/download/{model_name}")
async def download_model(model_name: str):
    """Download a specific model file for OTA update."""
    model_path = MODELS_DIR / model_name
    
    if not model_path.exists():
        raise HTTPException(status_code=404, detail=f"Model {model_name} not found")
    
    logger.info(f"Serving model download: {model_name}")
    return FileResponse(
        path=str(model_path),
        filename=model_name,
        media_type="application/octet-stream"
    )



@app.post("/api/detect", response_model=DetectionResponse)
async def detect_objects(file: UploadFile = File(...)):
    """
    YOLOv8 object detection endpoint.
    Accepts an image file and returns detected objects with bounding boxes.
    """
    if not yolo_interpreter:
        raise HTTPException(status_code=503, detail="YOLOv8 model not loaded")
    
    start_time = time.time()
    
    try:
        # Read and preprocess image
        image_bytes = await file.read()
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        original_width, original_height = image.size
        
        # Resize for YOLO (640x640)
        input_size = 640
        resized = image.resize((input_size, input_size), Image.BILINEAR)
        
        # Normalize to 0-1 range
        input_data = np.array(resized, dtype=np.float32) / 255.0
        input_data = np.expand_dims(input_data, axis=0)
        
        # Run inference
        input_details = yolo_interpreter.get_input_details()
        output_details = yolo_interpreter.get_output_details()
        
        yolo_interpreter.set_tensor(input_details[0]['index'], input_data)
        yolo_interpreter.invoke()
        
        output = yolo_interpreter.get_tensor(output_details[0]['index'])
        
        # Parse detections (YOLOv8 output format: [1, 84, 8400])
        detections = parse_yolo_output(output, original_width, original_height)
        
        inference_time = (time.time() - start_time) * 1000
        
        return DetectionResponse(
            success=True,
            detections=detections,
            inference_time_ms=round(inference_time, 2)
        )
        
    except Exception as e:
        logger.error(f"Detection error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/classify", response_model=ClassificationResponse)
async def classify_image(file: UploadFile = File(...)):
    """
    EfficientNet image classification endpoint.
    Accepts an image file and returns top classifications.
    """
    if not efficientnet_interpreter:
        raise HTTPException(status_code=503, detail="EfficientNet model not loaded")
    
    start_time = time.time()
    
    try:
        # Read and preprocess image
        image_bytes = await file.read()
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        
        # Resize for EfficientNet (224x224)
        input_size = 224
        resized = image.resize((input_size, input_size), Image.BILINEAR)
        
        # Get input details to determine data type
        input_details = efficientnet_interpreter.get_input_details()
        output_details = efficientnet_interpreter.get_output_details()
        
        input_dtype = input_details[0]['dtype']
        
        if input_dtype == np.uint8:
            # Quantized model expects uint8
            input_data = np.array(resized, dtype=np.uint8)
        else:
            # Float model expects normalized float32
            input_data = np.array(resized, dtype=np.float32) / 255.0
        
        input_data = np.expand_dims(input_data, axis=0)
        
        # Run inference
        efficientnet_interpreter.set_tensor(input_details[0]['index'], input_data)
        efficientnet_interpreter.invoke()
        
        output = efficientnet_interpreter.get_tensor(output_details[0]['index'])
        
        # Parse classifications
        classifications = parse_efficientnet_output(output)
        
        inference_time = (time.time() - start_time) * 1000
        
        return ClassificationResponse(
            success=True,
            classifications=classifications,
            inference_time_ms=round(inference_time, 2)
        )
        
    except Exception as e:
        logger.error(f"Classification error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


def parse_yolo_output(output: np.ndarray, orig_width: int, orig_height: int, 
                      conf_threshold: float = 0.25, iou_threshold: float = 0.45) -> List[Detection]:
    """Parse YOLOv8 output tensor to detection list."""
    detections = []
    
    # Output shape: [1, 84, 8400] -> transpose to [8400, 84]
    output = output[0].T
    
    num_classes = 80
    
    for detection in output:
        # Extract box coordinates (center_x, center_y, width, height)
        cx, cy, w, h = detection[:4]
        
        # Extract class scores
        class_scores = detection[4:4+num_classes]
        max_score = np.max(class_scores)
        class_id = np.argmax(class_scores)
        
        if max_score >= conf_threshold:
            # Convert to corner format and normalize
            x1 = (cx - w/2) / 640
            y1 = (cy - h/2) / 640
            x2 = (cx + w/2) / 640
            y2 = (cy + h/2) / 640
            
            # Clamp to 0-1 range
            x1, y1, x2, y2 = max(0, x1), max(0, y1), min(1, x2), min(1, y2)
            
            label = coco_labels[class_id] if class_id < len(coco_labels) else f"class_{class_id}"
            
            detections.append(Detection(
                label=label,
                confidence=float(max_score),
                bbox=[float(x1), float(y1), float(x2), float(y2)]
            ))
    
    # Apply NMS
    detections = apply_nms(detections, iou_threshold)
    
    return detections[:10]  # Return top 10


def apply_nms(detections: List[Detection], iou_threshold: float) -> List[Detection]:
    """Apply Non-Maximum Suppression to detection list."""
    if not detections:
        return []
    
    # Sort by confidence
    detections = sorted(detections, key=lambda x: x.confidence, reverse=True)
    
    keep = []
    while detections:
        best = detections.pop(0)
        keep.append(best)
        
        detections = [
            d for d in detections
            if compute_iou(best.bbox, d.bbox) < iou_threshold
        ]
    
    return keep


def compute_iou(box1: List[float], box2: List[float]) -> float:
    """Compute Intersection over Union of two boxes."""
    x1 = max(box1[0], box2[0])
    y1 = max(box1[1], box2[1])
    x2 = min(box1[2], box2[2])
    y2 = min(box1[3], box2[3])
    
    inter_area = max(0, x2 - x1) * max(0, y2 - y1)
    
    box1_area = (box1[2] - box1[0]) * (box1[3] - box1[1])
    box2_area = (box2[2] - box2[0]) * (box2[3] - box2[1])
    
    union_area = box1_area + box2_area - inter_area
    
    return inter_area / union_area if union_area > 0 else 0


def parse_efficientnet_output(output: np.ndarray, top_k: int = 3) -> List[Classification]:
    """Parse EfficientNet output tensor to classification list."""
    scores = output[0]
    
    # If output is uint8 (quantized), convert to float
    if scores.dtype == np.uint8:
        scores = scores.astype(np.float32) / 255.0
    
    # Apply softmax if not already applied
    if np.max(scores) > 1.0 or np.min(scores) < 0:
        exp_scores = np.exp(scores - np.max(scores))
        scores = exp_scores / np.sum(exp_scores)
    
    # Get top-k indices
    top_indices = np.argsort(scores)[-top_k:][::-1]
    
    classifications = []
    for idx in top_indices:
        label = imagenet_labels[idx] if idx < len(imagenet_labels) else f"class_{idx}"
        classifications.append(Classification(
            label=label,
            confidence=float(scores[idx])
        ))
    
    return classifications


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
