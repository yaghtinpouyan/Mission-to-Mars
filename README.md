This is Schrodinger's Cats NASA Space Apps Project! Our project is a navigation system on Mars specifically on the MC-17 area. It recognizes the slope and elevation of the terrain to create an optimized path from A to B.

## Run locally on Windows

Install JDK 17 or newer, then run `.\backend\run.bat` from PowerShell in the repository root (or invoke it by its full path). The script compiles the small static-file server into a temporary directory and serves the frontend on `http://127.0.0.1:8080`. All paths passed to `javac` are quoted, so the repository can be in a directory containing spaces. Stop the server with Ctrl+C.

Each teammate can run this on their own computer; this local setup does not require the project owner's Wi-Fi or hotspot. The current repository contains a static frontend, not a Java backend. See [HANDOVER.md](HANDOVER.md) for missing source/data needed to finish the translated elevation and `/hire` work.

https://docs.google.com/document/d/12UfRRfIpvsmfZS01p7Irl5x-2BcJA24kF_BwqBCHTM0/edit?tab=t.0#heading=h.f3zv6s1zls21
