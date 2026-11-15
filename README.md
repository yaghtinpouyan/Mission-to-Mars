This is Schrodinger's Cats NASA Space Apps Project! Our project is a navigation system on Mars specifically on the MC-17 area. It recognizes the slope and elevation of the terrain to create an optimized path from A to B.

## Run locally on Windows

Install JDK 17 or newer, then run `.\backend\run.bat` from PowerShell in the repository root (or invoke it by its full path). The script builds and starts the Java backend, which serves the frontend and route API at `http://localhost:8080`. Source file paths passed to `javac` are quoted, so checkouts in directories containing spaces are supported. Stop the server with Ctrl+C.

Each teammate can run the app locally without joining another developer's Wi-Fi or hotspot. The optional 463 m elevation raster is not included in Git; without it, the general route works but high-resolution route refinement is unavailable. See [data/hires/README.md](data/hires/README.md) for setup instructions and [docs/HANDOVER.md](docs/HANDOVER.md) for project status.

https://docs.google.com/document/d/12UfRRfIpvsmfZS01p7Irl5x-2BcJA24kF_BwqBCHTM0/edit?tab=t.0#heading=h.f3zv6s1zls21
