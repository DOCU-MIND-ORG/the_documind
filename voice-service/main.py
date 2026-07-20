import os
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel
import tempfile
import time

app = FastAPI(title="DocuMind Voice Service")

print("Loading Whisper model (this might take a moment the first time to download)...")
# Using the "tiny.en" model for best speed on English audio. 
# You can change to "base.en" or "small.en" for higher accuracy.
model_size = "tiny.en"
model = WhisperModel(model_size, device="cpu", compute_type="int8")
print("Whisper model loaded successfully!")

@app.post("/transcribe")
async def transcribe(audio: UploadFile = File(...)):
    if not audio:
        raise HTTPException(status_code=400, detail="No audio file provided")
    
    start_time = time.time()
    temp_file_path = None
    
    try:
        content = await audio.read()
        print(f"Received {audio.filename} ({len(content)} bytes)...")
        
        # Write to a temporary file for Whisper to read
        with tempfile.NamedTemporaryFile(delete=False, suffix=".webm") as temp_file:
            temp_file.write(content)
            temp_file_path = temp_file.name
            
        print("Transcribing...")
        # Transcribe the audio
        segments, info = model.transcribe(temp_file_path, beam_size=5)
        
        text = " ".join([segment.text for segment in segments]).strip()
        
        duration = time.time() - start_time
        print(f"Transcription complete in {duration:.2f}s. Result: {text}")
        
        return JSONResponse(content={"text": text})
        
    except Exception as e:
        print(f"Transcription error: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Transcription failed: {str(e)}")
    finally:
        # Clean up the temporary file
        if temp_file_path and os.path.exists(temp_file_path):
            try:
                os.remove(temp_file_path)
            except:
                pass

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8000)
