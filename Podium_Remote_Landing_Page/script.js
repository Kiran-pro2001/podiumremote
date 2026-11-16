/**
 * Podium Remote — Interactive Script & Cockpit Simulator
 * Designed for Kiran Kumar (Bengaluru, Bharat)
 * 365 Days, 365 Products Challenge — Day 004
 */

document.addEventListener("DOMContentLoaded", () => {
  initAmbientCanvas();
  initCockpitSimulator();
  initFaqAccordion();
  init3DParallaxTilt();
  initMobileNav();
  initFooterAccordion();
});

/* ==========================================================================
   1. Interactive Cockpit Simulator
   ========================================================================== */
function initCockpitSimulator() {
  const btnRecord = document.getElementById("sim-btn-record");
  const btnStop = document.getElementById("sim-btn-stop");
  const btnSwitchCam = document.getElementById("sim-btn-switch");
  const btnSnapshot = document.getElementById("sim-btn-snapshot");

  const tallyIndicator = document.getElementById("sim-tally");
  const timerDisplay = document.getElementById("sim-timer");
  const notificationText = document.getElementById("sim-notification");
  const currentCamText = document.getElementById("sim-cam-name");
  const vuBars = document.querySelectorAll(".vu-bar");
  const viewfinderBackdrop = document.getElementById("sim-viewfinder-img");
  const storageReadout = document.getElementById("sim-storage-readout");

  if (!btnRecord || !btnStop) return;

  let isRecording = false;
  let timerInterval = null;
  let vuInterval = null;
  let elapsedSeconds = 0;
  let currentCamera = "REAR (Wide 4K)";
  let currentStorageGb = 64.2;

  // Format seconds to HH:MM:SS
  function formatTime(seconds) {
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    return `${hrs.toString().padStart(2, "0")}:${mins.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
  }

  // Audio VU meter simulation
  function startAudioMeter() {
    vuInterval = setInterval(() => {
      vuBars.forEach((bar) => {
        const randomHeight = Math.floor(Math.random() * 80) + 15;
        bar.style.height = `${randomHeight}%`;
      });
    }, 120);
  }

  function stopAudioMeter() {
    clearInterval(vuInterval);
    vuBars.forEach((bar) => {
      bar.style.height = "15%";
    });
  }

  // Start Recording Action
  btnRecord.addEventListener("click", () => {
    if (isRecording) return;
    isRecording = true;

    // UI Updates
    tallyIndicator.classList.add("is-recording");
    tallyIndicator.querySelector(".tally-text").textContent = "REC ● LIVE";

    btnRecord.classList.add("is-recording");
    btnRecord.innerHTML = `
      <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <circle cx="12" cy="12" r="8"/>
      </svg>
      Recording Rolling...
    `;

    btnStop.classList.add("is-active");

    notificationText.innerHTML = `
      <strong style="color: #ff9da5;">● RECORDING STARTED:</strong> 
      Capturing pristine 4K video locally to Android device. Zero cloud delay.
    `;

    // Start Timer
    elapsedSeconds = 0;
    timerDisplay.textContent = formatTime(elapsedSeconds);
    timerInterval = setInterval(() => {
      elapsedSeconds++;
      timerDisplay.textContent = formatTime(elapsedSeconds);

      // Decrement simulated storage every 5 seconds
      if (elapsedSeconds % 5 === 0 && currentStorageGb > 5) {
        currentStorageGb = (currentStorageGb - 0.05);
        if (storageReadout) {
          storageReadout.textContent = `${currentStorageGb.toFixed(1)} GB Free`;
        }
      }
    }, 1000);

    startAudioMeter();
  });

  // Stop Recording Action
  btnStop.addEventListener("click", () => {
    if (!isRecording) return;
    isRecording = false;

    // UI Updates
    tallyIndicator.classList.remove("is-recording");
    tallyIndicator.querySelector(".tally-text").textContent = "STANDBY";

    btnRecord.classList.remove("is-recording");
    btnRecord.innerHTML = `
      <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <circle cx="12" cy="12" r="7"/>
      </svg>
      ● Start Recording
    `;

    btnStop.classList.remove("is-active");

    clearInterval(timerInterval);
    stopAudioMeter();

    const timestamp = new Date().toISOString().replace(/[-:T.]/g, "").slice(0, 14);
    notificationText.innerHTML = `
      <strong style="color: #8eae9d;">✓ RECORDING FINALIZED:</strong> 
      Saved to <code style="color: #c5a582;">/DCIM/Camera/VID_${timestamp}.mp4</code> (${elapsedSeconds}s • 4K 60fps)
    `;
  });

  // Switch Camera Action
  if (btnSwitchCam) {
    btnSwitchCam.addEventListener("click", () => {
      if (currentCamera.includes("REAR")) {
        currentCamera = "FRONT (Selfie 1080p)";
        if (viewfinderBackdrop) {
          viewfinderBackdrop.style.transform = "scaleX(-1)";
        }
      } else {
        currentCamera = "REAR (Wide 4K)";
        if (viewfinderBackdrop) {
          viewfinderBackdrop.style.transform = "scaleX(1)";
        }
      }

      if (currentCamText) {
        currentCamText.textContent = currentCamera;
      }

      notificationText.innerHTML = `
        <strong style="color: #c5a582;">⚙ CAMERA SWITCHED:</strong> Active lens is now <strong>${currentCamera}</strong>.
      `;
    });
  }

  // Check Framing Snapshot Action
  if (btnSnapshot) {
    btnSnapshot.addEventListener("click", () => {
      const monitor = document.querySelector(".viewfinder-monitor");
      if (monitor) {
        monitor.style.filter = "brightness(1.9) contrast(1.2)";
        setTimeout(() => {
          monitor.style.filter = "none";
        }, 180);
      }

      const now = new Date().toTimeString().split(" ")[0];
      notificationText.innerHTML = `
        <strong style="color: #8eae9d;">📸 SNAPSHOT CAPTURED:</strong> 
        Test frame verified at ${now}. Framing is centered and clear.
      `;
    });
  }
}

/* ==========================================================================
   2. Creative Cinema Viewfinder & Recording Canvas Animation
   ========================================================================== */
function initAmbientCanvas() {
  const canvas = document.getElementById("ambient-canvas");
  if (!canvas) return;

  const ctx = canvas.getContext("2d");
  let width = (canvas.width = window.innerWidth);
  let height = (canvas.height = window.innerHeight);

  window.addEventListener("resize", () => {
    width = canvas.width = window.innerWidth;
    height = canvas.height = window.innerHeight;
  });

  const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (prefersReducedMotion) return;

  // Floating Reticles & Lens Bokeh Disks
  const reticleCount = Math.min(8, Math.max(4, Math.floor(window.innerWidth / 220)));
  const reticles = [];
  const reticleLabels = ["4K UHD", "24 FPS", "ISO 400", "1/50s", "ƒ/1.8", "WIDE", "PRO-LOG", "REC ●"];

  for (let i = 0; i < reticleCount; i++) {
    reticles.push({
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.35,
      vy: (Math.random() - 0.5) * 0.35,
      size: Math.random() * 24 + 18,
      label: reticleLabels[i % reticleLabels.length],
      depth: Math.random() * 0.6 + 0.4,
      isTally: i % reticleLabels.length === 7,
    });
  }

  // Floating Optical Bokeh Disks
  const bokehCount = Math.min(18, Math.floor(window.innerWidth / 80));
  const bokehList = [];
  for (let i = 0; i < bokehCount; i++) {
    bokehList.push({
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.25,
      vy: (Math.random() - 0.5) * 0.25,
      radius: Math.random() * 30 + 10,
      alpha: Math.random() * 0.04 + 0.015,
      color: Math.random() > 0.6 ? "rgba(122, 154, 139," : "rgba(197, 165, 130,",
    });
  }

  let mouseX = width / 2;
  let mouseY = height / 2;
  let targetMouseX = mouseX;
  let targetMouseY = mouseY;
  let isTabActive = true;
  let scanlineY = 0;
  let timecodeFrame = 0;
  let pulseTimer = 0;

  document.addEventListener("visibilitychange", () => {
    isTabActive = !document.hidden;
  });

  window.addEventListener("mousemove", (e) => {
    targetMouseX = e.clientX;
    targetMouseY = e.clientY;
  });

  function render() {
    if (!isTabActive) {
      requestAnimationFrame(render);
      return;
    }

    ctx.clearRect(0, 0, width, height);

    // Smooth mouse lerp
    mouseX += (targetMouseX - mouseX) * 0.05;
    mouseY += (targetMouseY - mouseY) * 0.05;

    pulseTimer += 0.025;
    const breathingOpacity = Math.sin(pulseTimer) * 0.02 + 0.04;

    // 1. Subtle Rule-of-Thirds Grid Lines
    ctx.strokeStyle = `rgba(122, 154, 139, ${breathingOpacity})`;
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 12]);

    const thirdW = width / 3;
    const thirdH = height / 3;

    // Vertical third lines
    ctx.beginPath();
    ctx.moveTo(thirdW, 0);
    ctx.lineTo(thirdW, height);
    ctx.moveTo(thirdW * 2, 0);
    ctx.lineTo(thirdW * 2, height);
    // Horizontal third lines
    ctx.moveTo(0, thirdH);
    ctx.lineTo(width, thirdH);
    ctx.moveTo(0, thirdH * 2);
    ctx.lineTo(width, thirdH * 2);
    ctx.stroke();
    ctx.setLineDash([]);

    // 2. Viewfinder Corner Framing Brackets
    const margin = Math.min(48, width * 0.04);
    const bracketLen = 28;
    ctx.strokeStyle = "rgba(197, 165, 130, 0.18)";
    ctx.lineWidth = 1.5;

    // Top-Left ┌
    ctx.beginPath();
    ctx.moveTo(margin, margin + bracketLen);
    ctx.lineTo(margin, margin);
    ctx.lineTo(margin + bracketLen, margin);
    ctx.stroke();

    // Top-Right ┐
    ctx.beginPath();
    ctx.moveTo(width - margin - bracketLen, margin);
    ctx.lineTo(width - margin, margin);
    ctx.lineTo(width - margin, margin + bracketLen);
    ctx.stroke();

    // Bottom-Left └
    ctx.beginPath();
    ctx.moveTo(margin, height - margin - bracketLen);
    ctx.lineTo(margin, height - margin);
    ctx.lineTo(margin + bracketLen, height - margin);
    ctx.stroke();

    // Bottom-Right ┘
    ctx.beginPath();
    ctx.moveTo(width - margin - bracketLen, height - margin);
    ctx.lineTo(width - margin, height - margin);
    ctx.lineTo(width - margin, height - margin - bracketLen);
    ctx.stroke();

    // 3. Sweeping CMOS Camera Sensor Scanline Beam
    scanlineY = (scanlineY + 1.2) % (height + 150);
    const scanGradient = ctx.createLinearGradient(0, scanlineY - 75, 0, scanlineY + 75);
    scanGradient.addColorStop(0, "rgba(217, 119, 127, 0)");
    scanGradient.addColorStop(0.5, "rgba(217, 119, 127, 0.035)");
    scanGradient.addColorStop(1, "rgba(217, 119, 127, 0)");
    ctx.fillStyle = scanGradient;
    ctx.fillRect(0, scanlineY - 75, width, 150);

    // 4. Subtle Optical Bokeh Disks
    for (let i = 0; i < bokehList.length; i++) {
      const b = bokehList[i];
      b.x += b.vx;
      b.y += b.vy;

      if (b.x < -40) b.x = width + 40;
      if (b.x > width + 40) b.x = -40;
      if (b.y < -40) b.y = height + 40;
      if (b.y > height + 40) b.y = -40;

      ctx.beginPath();
      ctx.arc(b.x, b.y, b.radius, 0, Math.PI * 2);
      ctx.fillStyle = `${b.color}${b.alpha})`;
      ctx.fill();

      // Inner lens diaphragm ring
      ctx.beginPath();
      ctx.arc(b.x, b.y, b.radius * 0.7, 0, Math.PI * 2);
      ctx.strokeStyle = `${b.color}${b.alpha * 1.5})`;
      ctx.lineWidth = 0.75;
      ctx.stroke();
    }

    // 5. Drifting Camera Focus Reticles & Recording Telemetry
    timecodeFrame++;
    const totalFrames = timecodeFrame % 3600;
    const tcMin = String(Math.floor(totalFrames / 1800)).padStart(2, "0");
    const tcSec = String(Math.floor((totalFrames % 1800) / 30)).padStart(2, "0");
    const tcFr = String(totalFrames % 30).padStart(2, "0");

    for (let i = 0; i < reticles.length; i++) {
      const r = reticles[i];
      r.x += r.vx;
      r.y += r.vy;

      if (r.x < 50) r.x = width - 50;
      if (r.x > width - 50) r.x = 50;
      if (r.y < 80) r.y = height - 80;
      if (r.y > height - 80) r.y = 80;

      // Parallax offset relative to mouse
      const px = r.x + (mouseX - width / 2) * 0.02 * r.depth;
      const py = r.y + (mouseY - height / 2) * 0.02 * r.depth;

      // Render Focus Crosshair [ + ]
      ctx.strokeStyle = r.isTally ? "rgba(217, 119, 127, 0.45)" : "rgba(122, 154, 139, 0.28)";
      ctx.lineWidth = 1.2;

      const s = r.size;
      // Focus brackets
      ctx.beginPath();
      ctx.moveTo(px - s, py - s / 2);
      ctx.lineTo(px - s, py - s);
      ctx.lineTo(px - s / 2, py - s);

      ctx.moveTo(px + s / 2, py - s);
      ctx.lineTo(px + s, py - s);
      ctx.lineTo(px + s, py - s / 2);

      ctx.moveTo(px - s, py + s / 2);
      ctx.lineTo(px - s, py + s);
      ctx.lineTo(px - s / 2, py + s);

      ctx.moveTo(px + s / 2, py + s);
      ctx.lineTo(px + s, py + s);
      ctx.lineTo(px + s, py + s / 2);
      ctx.stroke();

      // Center cross mark
      ctx.beginPath();
      ctx.moveTo(px - 4, py);
      ctx.lineTo(px + 4, py);
      ctx.moveTo(px, py - 4);
      ctx.lineTo(px, py + 4);
      ctx.stroke();

      // Label
      ctx.font = "10px ui-monospace, SFMono-Regular, Menlo, monospace";
      ctx.fillStyle = r.isTally ? "rgba(217, 119, 127, 0.7)" : "rgba(197, 165, 130, 0.4)";
      ctx.fillText(r.label, px - s, py + s + 14);

      if (r.isTally) {
        // Pulsing red recording dot
        const tallyPulse = Math.sin(pulseTimer * 3) * 0.4 + 0.6;
        ctx.beginPath();
        ctx.arc(px + s, py - s, 4, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(217, 119, 127, ${tallyPulse})`;
        ctx.fill();
      }
    }

    // 6. Ambient Studio Timecode Watermark (Bottom Right)
    ctx.font = "11px ui-monospace, SFMono-Regular, Menlo, monospace";
    ctx.fillStyle = "rgba(122, 154, 139, 0.22)";
    ctx.textAlign = "right";
    ctx.fillText(`REC TC: 00:${tcMin}:${tcSec}:${tcFr} [STANDBY LAN]`, width - margin, height - margin + 20);
    ctx.textAlign = "left";

    requestAnimationFrame(render);
  }

  requestAnimationFrame(render);
}

/* ==========================================================================
   3. Objection-Crushing FAQ Accordion
   ========================================================================== */
function initFaqAccordion() {
  const faqItems = document.querySelectorAll(".faq-item");

  faqItems.forEach((item) => {
    const trigger = item.querySelector(".faq-trigger");
    if (!trigger) return;

    trigger.addEventListener("click", () => {
      const isOpen = item.classList.contains("is-open");

      // Close all other items
      faqItems.forEach((other) => {
        if (other !== item) {
          other.classList.remove("is-open");
          const otherTrigger = other.querySelector(".faq-trigger");
          if (otherTrigger) otherTrigger.setAttribute("aria-expanded", "false");
        }
      });

      // Toggle current item
      if (isOpen) {
        item.classList.remove("is-open");
        trigger.setAttribute("aria-expanded", "false");
      } else {
        item.classList.add("is-open");
        trigger.setAttribute("aria-expanded", "true");
      }
    });
  });
}

/* ==========================================================================
   4. Desktop 3D Parallax Tilt (Max 4.5deg lerp)
   ========================================================================== */
function init3DParallaxTilt() {
  if (window.innerWidth < 1024) return;
  const tiltCards = document.querySelectorAll(".matrix-card.flagship, .pricing-flagship-card, .feature-card");

  tiltCards.forEach((card) => {
    card.addEventListener("mousemove", (e) => {
      const rect = card.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;

      const centerX = rect.width / 2;
      const centerY = rect.height / 2;

      const deltaX = (x - centerX) / centerX;
      const deltaY = (y - centerY) / centerY;

      const rotateX = -deltaY * 3.5;
      const rotateY = deltaX * 3.5;

      card.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) translateY(-4px)`;
    });

    card.addEventListener("mouseleave", () => {
      card.style.transform = "";
    });
  });
}

/* ==========================================================================
   5. Mobile Navigation Drawer (Hamburger)
   ========================================================================== */
function initMobileNav() {
  const hamburgerBtn = document.getElementById("hamburgerBtn");
  const mobileNavDrawer = document.getElementById("mobileNavDrawer");
  const mobileNavClose = document.getElementById("mobileNavClose");
  const mobileNavBackdrop = document.getElementById("mobileNavBackdrop");
  const mobileNavItems = document.querySelectorAll(".mobile-nav-item, .mobile-nav-cta .btn");

  if (!hamburgerBtn || !mobileNavDrawer) return;

  function openDrawer() {
    hamburgerBtn.classList.add("is-active");
    hamburgerBtn.setAttribute("aria-expanded", "true");
    mobileNavDrawer.classList.add("is-open");
    mobileNavDrawer.setAttribute("aria-hidden", "false");
    document.body.style.overflow = "hidden";
  }

  function closeDrawer() {
    hamburgerBtn.classList.remove("is-active");
    hamburgerBtn.setAttribute("aria-expanded", "false");
    mobileNavDrawer.classList.remove("is-open");
    mobileNavDrawer.setAttribute("aria-hidden", "true");
    document.body.style.overflow = "";
  }

  hamburgerBtn.addEventListener("click", () => {
    const isOpen = mobileNavDrawer.classList.contains("is-open");
    if (isOpen) {
      closeDrawer();
    } else {
      openDrawer();
    }
  });

  if (mobileNavClose) {
    mobileNavClose.addEventListener("click", closeDrawer);
  }

  if (mobileNavBackdrop) {
    mobileNavBackdrop.addEventListener("click", closeDrawer);
  }

  mobileNavItems.forEach((item) => {
    item.addEventListener("click", closeDrawer);
  });
}

/* ==========================================================================
   6. Mobile Footer Accordion Dropdowns
   ========================================================================== */
function initFooterAccordion() {
  const toggles = document.querySelectorAll(".footer-accordion-toggle");

  toggles.forEach((toggle) => {
    toggle.addEventListener("click", () => {
      // Only active on mobile viewports (< 768px)
      if (window.innerWidth > 768) return;

      const parentAccordion = toggle.closest(".footer-accordion");
      if (!parentAccordion) return;

      const isOpen = parentAccordion.classList.contains("is-open");

      // Close other accordions for clean single-drawer behavior
      document.querySelectorAll(".footer-accordion").forEach((acc) => {
        if (acc !== parentAccordion) {
          acc.classList.remove("is-open");
          const btn = acc.querySelector(".footer-accordion-toggle");
          if (btn) btn.setAttribute("aria-expanded", "false");
        }
      });

      if (isOpen) {
        parentAccordion.classList.remove("is-open");
        toggle.setAttribute("aria-expanded", "false");
      } else {
        parentAccordion.classList.add("is-open");
        toggle.setAttribute("aria-expanded", "true");
      }
    });
  });
}
