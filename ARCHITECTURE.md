# Arsitektur Aplikasi Deteksi Bahasa Isyarat BISINDO

## Diagram Arsitektur Sistem

```mermaid
graph TB
    A[Kamera Depan] --> B[CameraX Preview]
    B --> C[ImageAnalysis]
    C --> D[HandSignAnalyzer]
    
    D --> E[MediaPipe Hand Landmarker]
    E --> F[HandLandmarkerHelper]
    F --> G[Normalisasi Landmarks]
    
    G --> H[InterpreterHelper]
    H --> I[Model TensorFlow Lite<br/>model_bisindo.tflite]
    I --> J[Klasifikasi Huruf A-Z]
    
    J --> K[Smoothing & Filtering]
    K --> L[Dynamic Threshold]
    L --> M[UI Display]
    
    M --> N[Huruf Besar<br/>72sp]
    M --> O[Status Akurasi]
    M --> P[Debug Information]
    
    Q[Labels.txt<br/>A-Z] --> J
    R[Hand Landmarker Task] --> E
    
    style A fill:#e1f5fe
    style I fill:#f3e5f5
    style N fill:#e8f5e8
    style O fill:#fff3e0
    style P fill:#fce4ec
```

## Flow Deteksi Real-time

```mermaid
sequenceDiagram
    participant C as Camera
    participant A as Analyzer
    participant M as MediaPipe
    participant T as TensorFlow
    participant U as UI
    
    C->>A: Frame Image
    A->>M: Detect Hand Landmarks
    M->>A: 21 Landmarks per Hand
    A->>A: Normalize & Flatten (126 values)
    A->>T: Classify Gesture
    T->>A: Confidence Scores (26 classes)
    A->>A: Apply Smoothing Filter
    A->>A: Dynamic Threshold Check
    A->>U: Display Result
    U->>U: Show Letter + Status
```

## Komponen Utama

### 1. Input Layer
- **CameraX**: Pengambilan frame real-time
- **ImageAnalysis**: Analisis frame untuk deteksi tangan

### 2. Detection Layer
- **MediaPipe Hand Landmarker**: Deteksi 21 landmark per tangan
- **HandLandmarkerHelper**: Normalisasi dan preprocessing landmarks

### 3. Classification Layer
- **TensorFlow Lite Model**: Klasifikasi gestur menjadi huruf A-Z
- **InterpreterHelper**: Interface untuk model inference

### 4. Processing Layer
- **Smoothing**: Mengurangi flickering dengan history buffer
- **Dynamic Threshold**: Menyesuaikan threshold berdasarkan confidence
- **Stability Check**: Memastikan prediksi konsisten

### 5. Display Layer
- **Large Letter Display**: Huruf dengan ukuran 72sp
- **Status Indicators**: Akurasi dengan color coding
- **Debug Panel**: Informasi real-time untuk monitoring

## Fitur Peningkatan yang Ditambahkan

### 1. Akurasi Model
- ✅ Validasi data landmarks sebelum inference
- ✅ Logging detail untuk debugging
- ✅ Pengukuran waktu inference
- ✅ Top 3 predictions logging

### 2. Threshold Dinamis
- ✅ Menyesuaikan threshold berdasarkan confidence
- ✅ Range threshold: 40% - 80%
- ✅ Adaptif terhadap kondisi deteksi

### 3. Smoothing Prediksi
- ✅ History buffer 5 frame
- ✅ Most frequent prediction
- ✅ Stability counter (min 3 frames)
- ✅ Mengurangi flickering

### 4. UI yang Responsif
- ✅ Huruf besar dengan shadow elevation
- ✅ Color-coded status indicators
- ✅ Debug toggle switch
- ✅ Real-time accuracy stats
- ✅ Detailed landmark information

### 5. Debug Features
- ✅ Toggle debug mode
- ✅ Real-time accuracy percentage
- ✅ Landmark detection rate
- ✅ Dynamic threshold display
- ✅ Stability counter
- ✅ Inference timing

## Performa Optimasi

### Memory Management
- Efficient bitmap conversion
- Proper resource cleanup
- Optimized buffer allocation

### Processing Speed
- Single thread executor untuk analyzer
- Optimized landmark normalization
- Efficient tensor buffer operations

### Accuracy Improvements
- Dynamic threshold adjustment
- Prediction smoothing
- Landmark validation
- Stability requirements

Aplikasi ini sekarang memiliki sistem deteksi bahasa isyarat yang lebih akurat, responsif, dan user-friendly dengan fitur debug yang komprehensif untuk memverifikasi performa model secara real-time.
