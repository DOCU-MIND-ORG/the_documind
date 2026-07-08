# DocuMind Backend

The backend for **DocuMind**, a robust Spring Boot application providing AI capabilities and document processing for the platform.

## 🚀 Technologies Used
- **Java 21**
- **Spring Boot 3.5.6** (WebFlux, Data JPA, Security)
- **Spring AI** (Google GenAI integration, Pinecone Store)
- **PostgreSQL** (Relational Database)
- **Redis** (Caching)
- **JWT** (Authentication)
- **PDFBox** (PDF processing)
- **ONNX Runtime & HuggingFace Tokenizers** (Local processing/embeddings)
- **Cloudinary** (Media storage)
- **Jsoup & CommonMark** (HTML and Markdown processing)

## 📦 Getting Started

### Prerequisites
- **Java 21 JDK** installed.
- **Maven** (optional, you can use the provided wrapper `mvnw`).
- **PostgreSQL** and **Redis** instances running locally or remotely.

### Configuration and Third-Party Services
Configure the `.env` file in the root of the `backend` folder by filling in the empty values with your API keys. *(Note: `gemini_api_keys.txt` is excluded via `.gitignore`)*.

You will need to set up the following third-party services to get your API keys:
- **PostgreSQL Database**: Create a database instance on [Supabase](https://supabase.com/) or [AWS RDS](https://aws.amazon.com/rds/).
- **Google Gemini API**: Get your API key from [Google AI Studio](https://aistudio.google.com/).
- **SMTP Mail Server**: You can use [Gmail App Passwords](https://myaccount.google.com/apppasswords) or [SendGrid](https://sendgrid.com/).
- **Pinecone (Vector Database)**: Sign up at [Pinecone](https://www.pinecone.io/). 
  > **IMPORTANT:** This project uses **two separate Pinecone accounts** to isolate data. One account is used exclusively for storing **session history** (`PINECONE_MEMORY_KEY`, `PINECONE_MEMORY_INDEX_NAME`), and the other account is used exclusively for storing **documents and files** (`PINECONE_API_KEY`). Make sure you configure both separately.
- **Cloudinary**: Get your connection URL from [Cloudinary](https://cloudinary.com/) for media storage.
- **Redis**: Set up a free cloud Redis instance on [Redis Enterprise Cloud](https://redis.com/try-free/) or run it locally.
- **JWT Secret**: Generate a secure 256-bit hex string for authentication (`JWT_SECRET`).

### ONNX Cross-Encoder Models
The backend utilizes ONNX Runtime and HuggingFace Tokenizers for local processing and embeddings. Specifically, it uses the **`ms-marco-MiniLM-L-6-v2`** model.

✅ **Already Included:** This ONNX model is already tracked and included in the Git repository at `src/main/resources/onnx/ms-marco-MiniLM-L-6-v2/model.onnx`, so no immediate action is required on your part!

**If Missing:** If for any reason the model is missing or you need to re-download it, you must place the ONNX version of the cross-encoder model in the `src/main/resources/onnx/ms-marco-MiniLM-L-6-v2/` directory before starting the application.

🔗 **Model Link:** You can find the model and its details on HuggingFace here:  
[https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2](https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2)  
*(Note: Ensure you are downloading or exporting the `.onnx` format version for use with the ONNX Runtime).*

### Pre-downloading Dependencies (Faster Startup)
Unlike Python which uses `requirements.txt`, Java uses Maven (`pom.xml`) to manage dependencies. To download all dependencies in advance (so your application starts up faster later), you can run the provided scripts:
- **Windows**: Double click `download_dependencies.bat` or run `.\download_dependencies.bat`
- **Mac/Linux**: Run `./download_dependencies.sh` (you may need to run `chmod +x download_dependencies.sh` first)
Alternatively, you can just run `.\mvnw.cmd dependency:go-offline`.

### Running the Application

1. Navigate to the backend directory:
   ```bash
   cd backend
   ```
2. Run the Spring Boot application using the Maven wrapper:
   ```bash
   .\mvnw.cmd spring-boot:run
   ```
   *(On Unix/Mac use `./mvnw spring-boot:run`)*

The API will typically start on `http://localhost:8080`.

## 📚 API Documentation
This project includes OpenAPI integration. Once the application is running, you can access the API documentation UI at:
- `http://localhost:8080/webjars/swagger-ui/index.html` (or the configured swagger-ui endpoint)

## 🧹 Cleaning the Project
If you ever need to clean the project (e.g., to resolve caching issues, remove compiled classes, or before a fresh build), you can run the Maven clean command. This removes the `target` directory where compiled files are stored:

```bash
.\mvnw.cmd clean
```
*(On Unix/Mac use `./mvnw clean`)*

To both clean and compile the project from scratch, use:
```bash
.\mvnw.cmd clean install
```

## 🧪 Testing
To run the test suite:
```bash
.\mvnw.cmd test
```