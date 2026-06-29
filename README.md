# FLOLA - Linear Algebra Editor

[한국어](README_ko.md) | **English**

![Java](https://img.shields.io/badge/Java-25-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-26.0.1-0095D5)
![License](https://img.shields.io/badge/license-MIT-blue)
![Release](https://img.shields.io/github/v/release/hemisus/flola?include_prereleases)

<div align="center">
  <img src="docs/images/flola.png" width="400">
</div>

**FLOLA (Flow of Linear Algebra)** is a comprehensive linear algebra editor that lets you run operations easily, visualize complex computation flows, and save them as node graphs.

- Create and edit vectors, matrices, and multi-dimensional tensor data **without writing any code**.
- Instantly get results using 24 built-in operations including arithmetic, projection, and matrix decomposition.
- Connect nodes to visualize and save complex computation processes at a glance.
- Encapsulate your own operation graphs as reusable custom nodes — making it easy to **build and edit complex structures like neural network layers**.

Originally it was my university final assignment, I rebuilt and polished into a standalone application.

<br>

## 📸 Demo

<div align="center">
  <img src="docs/images/TensorEditor.gif" width="750">
</div>
<div align="center">
  <img src="docs/images/CustomOpr.gif" width="750">
</div>
<div align="center">
  <img src="docs/images/Projection.gif" width="750">
</div>

<br>

## ⬇️ Download (Ready to Run)

1. Download the latest `FLOLA-vX.X.X-windows-x64.zip` from the [**Releases**](https://github.com/hemisus/flola/releases) page.
2. Extract the zip and run **`FLOLA.exe`**. — **No Java installation required** (the runtime is bundled).

> Currently only **Windows 64-bit** builds are provided.
>
> ⚠️ The app is not code-signed, so Windows SmartScreen may show a warning ("Windows protected your PC") on first launch. Click **More info → Run anyway** to start it.

<br>

## ✨ Features

### Tensor Data Editor
- Create and edit scalars, vectors, matrices, and higher-dimensional tensors directly
- Supports shape changes, axis naming, and other detailed editing options
- NumPy-style broadcasting rules supported

### Built-in Operations (24 total)

| Category | Operations |
|---|---|
| **Basic** | Add, Subtract, Matrix Multiply, Elementwise Multiply, Divide, Negate, Transpose, Clear, Sum, Average |
| **Activation** | ReLU, Sigmoid, Tanh, Softmax |
| **Advanced** | Projection, Concatenate, Split, View (reshape), Conv2D, ConvTranspose2D, MaxPool2D, Upsample, SVD, Eigenvalues |

### Node-based Graph Editor
- Drag nodes onto the canvas and connect them
- **Multi-level Undo / Redo** for all edits — node add/remove, move, connect, value change, and more
- Canvas zoom, pan, multi-select, and copy/paste

### Custom Nodes (Subgraphs)
- Encapsulate any operation graph into a single reusable custom node
- Build and reuse complex structures like neural network layers with ease

<br>

## 🛠 Tech Stack

- **Language**: Java 25
- **GUI**: JavaFX 26.0.1 (`javafx.controls`, `javafx.fxml`, `javafx.graphics`)
- **Serialization**: Gson 2.10.1 — graph and tensor save/load
- **Build**: Maven (`javafx-maven-plugin`, `maven-shade-plugin`)
- **Distribution**: `jpackage` — self-contained executable with bundled runtime

<br>

## 📁 Project Structure

```
com.hemisus.flola
├── controller  # MainController, GraphCommand infrastructure (Undo/Redo)
├── event       # Event interfaces (ShapeChangeListener, etc.)
├── model       # Core domain: Graph, GraphNode, OperationNode, TensorNode,
│               # Tensor, CustomOperation, CustomOperationNode, GenericOperationNode
├── ui          # CanvasPane, NodeView, PortView, ConnectionLayer,
│               # TensorEditorStage, OperationEditorStage, and other editor UI
├── utils       # TensorOperations, OperationRegistry, GraphStorageJson, DataConverter
└── viewmodel   # NodeViewModel, TensorViewModel — editor UI state and Undo/Redo drafts
```

<br>

## 🚀 Build from Source

**Requirements**: JDK 25+, Maven

```bash
git clone https://github.com/hemisus/flola.git
cd flola

# Run in development mode
mvn clean javafx:run

# Build executable jar  →  target/flola.jar
mvn clean package
java -jar target/flola.jar      # requires JDK 25 installed
```

### Packaging as a standalone .exe — jpackage

Use `jpackage` to create a self-contained Windows app image with a bundled runtime. Output: `dist/FLOLA/FLOLA.exe`

```bat
mvn clean package
mkdir jpackage-input
copy target\flola.jar jpackage-input\
jpackage --type app-image --name FLOLA --input jpackage-input ^
         --main-jar flola.jar --main-class com.hemisus.flola.Launcher ^
         --app-version X.X.X --icon flola.ico --dest dist
```

<br>

## Roadmap

I plan to keep refining the UI and features over time as I use the app myself.

The app has been tested as thoroughly as possible, but bugs may still exist. If you find any issues or have suggestions, feel free to open a [Discussion](https://github.com/hemisus/flola/discussions) or reach out directly.

<br>

## 📄 License

This project is licensed under the [MIT License](LICENSE).

<br>

## 📬 Contact

- Email: hydro6323@gmail.com
- GitHub: [@hemisus](https://github.com/hemisus)
- Discord: hemisus_
