// 1. Find the page elements needed
const condSelect = document.getElementById("cond");
const layerSelect = document.getElementById("layer");
const exagSlider = document.getElementById("exag");
const summaryEl = document.getElementById("summary");
const verdictEl = document.getElementById("verdict");
const stopsEl = document.getElementById("stops");
const viewerEl = document.getElementById("viewer");

// 2. Placeholder viewer
// 3D model over here.
// The function NAMES must stay the same, because this controls them and then we would be cooked like so bad
const viewer = {
  setCondition(mode)   { console.log("condition:", mode);   showState(); },
  setLayer(name)       { console.log("layer:", name);       showState(); },
  setExaggeration(n)   { console.log("exaggeration:", n);   showState(); },
};

function showState() {
  viewerEl.textContent =
    `3D viewer goes here (condition: ${condSelect.value}, ` +
    `layer: ${layerSelect.value}, exaggeration: ${exagSlider.value}x)`;
}

// 3. Load route data and fill the summary panel
let routeData = null;

async function loadRoute() {
  try {
    const response = await fetch("route.json");
    routeData = await response.json();
    renderSummary();
  } catch (err) {
  
    summaryEl.textContent = "Could not load route.json. Run the page with Live Server.";
    console.error(err);
  }
}

function renderSummary() {
  if (!routeData) return;
  const r = routeData[condSelect.value];

  summaryEl.innerHTML = `
    <div class="row">Mode<b>${r.mode}</b></div>
    <div class="row">Distance<b>${r.distanceKm.toFixed(2)} km</b></div>
    <div class="row">Surface time<b>${r.timeHours.toFixed(1)} h</b></div>
    <div class="row">Science stops<b>${r.stops.length}</b></div>`;

  const go = r.verdict === "GO";
  verdictEl.style.color = go ? "var(--ok)" : "var(--bad)";
  verdictEl.textContent = go ? `GO: ${r.marginHours.toFixed(1)} h of margin` : "NO-GO";

  stopsEl.innerHTML = r.stops
    .map(s => `<div class="stop"><b>${s.name}</b><br>${s.why}</div>`)
    .join("");
}

// 4. Connect the controls 
condSelect.addEventListener("change", () => {
  viewer.setCondition(condSelect.value);
  renderSummary();
});
layerSelect.addEventListener("change", () => viewer.setLayer(layerSelect.value));
exagSlider.addEventListener("input", () => viewer.setExaggeration(Number(exagSlider.value)));

//  5. Start 
showState();
loadRoute();
