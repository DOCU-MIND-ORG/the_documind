from optimum.onnxruntime import ORTModelForSequenceClassification
from transformers import AutoTokenizer

model_id = "cross-encoder/ms-marco-MiniLM-L-6-v2"
output_dir = "src/main/resources/onnx/ms-marco-MiniLM-L-6-v2"

# Load model and export to ONNX
ort_model = ORTModelForSequenceClassification.from_pretrained(model_id, export=True)
tokenizer = AutoTokenizer.from_pretrained(model_id)

# Save the ONNX model and tokenizer
ort_model.save_pretrained(output_dir)
tokenizer.save_pretrained(output_dir)

print(f"Exported {model_id} to {output_dir}")
