# DocuMind

Welcome to the DocuMind project! DocuMind is an AI-powered document analysis and Q&A system.

This project is divided into two main components:
1. **Frontend**: A React and Vite-based web application.
2. **Backend**: A Java Spring Boot application.

## Project Structure & Documentation

To easily navigate and run the project, please refer to the following **3 README files**:
- **Root README** (`README.md`): This file, providing an overview and initial setup instructions.
- **Frontend README** (`frontend/README.md`): Contains instructions on how to set up, build, and run the React frontend.
- **Backend README** (`backend/README.md`): Contains instructions on how to set up, configure, and run the Spring Boot backend.

## Environment Variables & Third-Party Services

Before running the backend, you must configure the `.env` file located in the `backend/` directory. We have provided a template with empty values in `backend/.env`. You will need to connect the following third-party services and provide your own API keys/credentials:

1. **PostgreSQL Database** (e.g., Supabase, RDS) for relational data storage (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).
2. **Google Gemini API** for generative AI features (`GEMINI_API_KEY`).
3. **SMTP Mail Server** (e.g., Gmail, SendGrid) for email notifications (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`).
4. **Pinecone** for vector database storage (`PINECONE_MEMORY_KEY`, `PINECONE_MEMORY_INDEX_NAME`, `PINECONE_API_KEY`).
5. **Cloudinary** for media and file storage (`CLOUDINARY_URL`).
6. **Redis** for caching (`REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`).
7. **JWT Secret** - Please generate a secure random string for JWT authentication (`JWT_SECRET`).

---

Once the `.env` values are configured, please proceed to the `backend/README.md` and `frontend/README.md` to start the respective servers.
