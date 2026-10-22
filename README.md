# CST3492 Course TA Assistant (IntelliJ IDEA Plugin)

An AI-powered Teaching Assistant (TA) plugin for **CST3492 - Java Enterprise Application Development** (AY2026-2027 Semester 1, Assignment A - F0).

## Features (F0: Basic Functionalities)

1. **Course-Specific RAG Q&A Chatbot with Verifiable Source Citations**
   - **Document Parsing & Page-Scope Chunking**: Parses course lecture PDFs (via Apache PDFBox with Apache Tika MIME detection) per page and segments them into overlapping knowledge chunks with collision-free IDs while preserving exact document name and page number/scope metadata.
   - **Hybrid Retrieval Engine**: Combines **Okapi BM25 Inverted Keyword Index** and **Domain-Lexicon & Feature-Hashing Vector Index** (following Lecture 5 Slide 24-27) merged via **Reciprocal Rank Fusion (RRF)** (`k = 60`).
   - **Strict Source Citation & General-Knowledge Fallback**:
     - Verifies that every cited `[ChunkID: ...]` or `[Source: <doc>, Page <n>]` matches a passage actually supplied by Java, stripping any hallucinated citations.
     - Explicitly states *"Response is based on general knowledge; no specific course material is referenced."* when no relevant course material matches the query or when the LLM does not cite supplied course chunks.

2. **Embedded IntelliJ IDEA User Interface**
   - Right-side **Tool Window** (`CST3492 TA`) featuring multi-turn Q&A chat cards, highlighted source verification banners, async course slide importer (`Import Slides`), and LLM API settings dialog (`LLM Settings`).

3. **Editor Programming Context Awareness**
   - **Right-Click Context Menu Action** (`Ask CST3492 TA About Selected Code`): Captures selected Java code, file name, surrounding class/method context, and line numbers in the active editor, allowing users to ask targeted questions about their code.

## Tech Stack
- **Platform**: IntelliJ Platform SDK (`org.jetbrains.intellij.platform` 2.19.0, Java 21+)
- **Document Processing**: Apache PDFBox 3.0.3 & Apache Tika 2.9.2
- **LLM Integration**: JDK `HttpClient` + Jackson 2.18.2 (compatible with Tongji AI Services Platform `https://agi.tongji.edu.cn`, DeepSeek API `https://api.deepseek.com/v1`, and deterministic offline course TA mode)
- **Testing**: JUnit 5

## Build & Run
```bash
# Run unit tests
./gradlew test

# Build installable plugin archive (build/distributions/cst3492-ta-plugin-1.0.0.zip)
./gradlew buildPlugin

# Launch IntelliJ IDEA sandbox with the plugin installed
./gradlew runIde
```
