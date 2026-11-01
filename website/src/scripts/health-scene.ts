import * as THREE from 'three';

const PARTICLE_COUNT = 900;
const STATIC_TIME = 18;
const MAX_DPR = 1.5;

const particleVertexShader = /* glsl */ `
  uniform float uTime;
  uniform float uPixelRatio;
  attribute float aSeed;
  attribute float aSize;
  varying float vSeed;

  void main() {
    vec3 p = position;
    float phase = uTime * 0.16 + aSeed * 6.2831853;
    p.x += sin(phase + position.y * 0.8) * 0.16;
    p.y += cos(phase * 0.83 + position.x * 0.5) * 0.12;
    p.z += sin(phase * 0.67 + position.x) * 0.14;

    vec4 viewPosition = modelViewMatrix * vec4(p, 1.0);
    gl_Position = projectionMatrix * viewPosition;
    gl_PointSize = clamp(
      aSize * uPixelRatio * (16.0 / max(1.0, -viewPosition.z)),
      1.0,
      8.0
    );
    vSeed = aSeed;
  }
`;

const particleFragmentShader = /* glsl */ `
  varying float vSeed;

  void main() {
    float distanceToCenter = length(gl_PointCoord - vec2(0.5));
    if (distanceToCenter > 0.5) discard;

    float softness = 1.0 - smoothstep(0.08, 0.5, distanceToCenter);
    vec3 teal = vec3(0.08, 0.58, 0.61);
    vec3 aqua = vec3(0.39, 0.94, 0.87);
    vec3 color = mix(teal, aqua, vSeed);
    gl_FragColor = vec4(color, softness * 0.62);
  }
`;

const recordVertexShader = /* glsl */ `
  varying vec2 vUv;
  varying vec3 vWorldNormal;
  varying vec3 vWorldPosition;

  void main() {
    vUv = uv;
    vec4 worldPosition = modelMatrix * vec4(position, 1.0);
    vWorldPosition = worldPosition.xyz;
    vWorldNormal = normalize(mat3(modelMatrix) * normal);
    gl_Position = projectionMatrix * viewMatrix * worldPosition;
  }
`;

const recordFragmentShader = /* glsl */ `
  uniform float uTime;
  uniform float uStage;
  varying vec2 vUv;
  varying vec3 vWorldNormal;
  varying vec3 vWorldPosition;

  float recordBar(vec2 point, vec2 halfSize) {
    vec2 distanceToEdge = abs(point) - halfSize;
    return 1.0 - smoothstep(0.0, 0.012, max(distanceToEdge.x, distanceToEdge.y));
  }

  void main() {
    vec2 gridCell = abs(fract(vUv * vec2(10.0, 6.0)) - 0.5);
    float grid = smoothstep(0.465, 0.5, max(gridCell.x, gridCell.y));

    vec2 edgeDistance = min(vUv, 1.0 - vUv);
    float edge = 1.0 - smoothstep(0.0, 0.018, min(edgeDistance.x, edgeDistance.y));

    vec3 viewDirection = normalize(cameraPosition - vWorldPosition);
    float fresnel = pow(
      1.0 - abs(dot(normalize(vWorldNormal), viewDirection)),
      2.0
    );

    float scanPosition = fract(uTime * 0.085 + uStage * 0.32);
    float scan = exp(-pow((vUv.y - scanPosition) * 18.0, 2.0));

    float bars = 0.0;
    for (int i = 0; i < 4; i++) {
      float row = float(i);
      bars += recordBar(
        vUv - vec2(0.39, 0.30 + row * 0.115),
        vec2(0.22 - row * 0.022, 0.011)
      );
    }

    float nodeDistance = length(vUv - vec2(0.79, 0.50));
    float nodeRing = 1.0 - smoothstep(0.008, 0.018, abs(nodeDistance - 0.095));
    float nodeCore = 1.0 - smoothstep(0.018, 0.042, nodeDistance);
    float confirmation = (nodeRing + nodeCore * 0.8) * uStage;
    float pulse = 0.5 + 0.5 * sin(uTime * 1.25);

    vec3 navy = vec3(0.025, 0.12, 0.20);
    vec3 teal = vec3(0.07, 0.53, 0.57);
    vec3 aqua = vec3(0.38, 0.93, 0.85);
    vec3 color = mix(navy, teal, 0.42 + grid * 0.18 + edge * 0.35);
    color = mix(color, aqua, clamp(scan * 0.28 + confirmation * 0.72, 0.0, 1.0));

    float alpha = 0.075 + grid * 0.045 + edge * 0.34 + bars * 0.24;
    alpha += scan * 0.12 + fresnel * 0.18;
    alpha += confirmation * (0.28 + pulse * 0.16);
    gl_FragColor = vec4(color, clamp(alpha, 0.0, 0.72));
  }
`;

/** Mounts the decorative, procedural health-record scene on a canvas. */
export function mountHealthScene(canvas: HTMLCanvasElement): void {
  canvas.dataset.webglReady = 'false';
  canvas.dataset.frame = '0';

  let renderer: THREE.WebGLRenderer;
  try {
    renderer = new THREE.WebGLRenderer({
      canvas,
      alpha: true,
      antialias: true,
      powerPreference: 'low-power',
    });
  } catch {
    return;
  }

  const hostWindow = canvas.ownerDocument.defaultView;
  if (!hostWindow) {
    renderer.dispose();
    return;
  }
  const window: Window = hostWindow;
  const document = canvas.ownerDocument;
  const geometries: THREE.BufferGeometry[] = [];
  const materials: THREE.Material[] = [];

  try {
    renderer.setClearColor(0x000000, 0);
    renderer.outputColorSpace = THREE.SRGBColorSpace;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 100);
    const lookAtTarget = new THREE.Vector3(0, 0, 0);
    const sceneRoot = new THREE.Group();
    scene.add(sceneRoot);

    const particleGeometry = new THREE.BufferGeometry();
    const positions = new Float32Array(PARTICLE_COUNT * 3);
    const seeds = new Float32Array(PARTICLE_COUNT);
    const sizes = new Float32Array(PARTICLE_COUNT);

    // A deterministic hash keeps the composition stable between visits.
    let randomState = 0x2f6e2b1;
    const random = (): number => {
      randomState = (Math.imul(randomState, 1664525) + 1013904223) >>> 0;
      return randomState / 4294967296;
    };

    for (let i = 0; i < PARTICLE_COUNT; i += 1) {
      const index = i * 3;
      positions[index] = (random() - 0.5) * 10;
      positions[index + 1] = (random() - 0.5) * 6;
      positions[index + 2] = (random() - 0.5) * 6;
      seeds[i] = random();
      sizes[i] = 0.8 + random() * 1.8;
    }

    particleGeometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    particleGeometry.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 1));
    particleGeometry.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1));
    const particleMaterial = new THREE.ShaderMaterial({
      uniforms: {
        uTime: { value: STATIC_TIME },
        uPixelRatio: { value: 1 },
      },
      vertexShader: particleVertexShader,
      fragmentShader: particleFragmentShader,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    });
    geometries.push(particleGeometry);
    materials.push(particleMaterial);
    sceneRoot.add(new THREE.Points(particleGeometry, particleMaterial));

    const orbitalGroup = new THREE.Group();
    const bandSettings = [
      { radius: 3.65, y: -0.58, tilt: [0.34, 0.12, -0.20], color: 0x248c94, opacity: 0.34 },
      { radius: 3.15, y: 0.10, tilt: [-0.28, -0.20, 0.24], color: 0x52e4d3, opacity: 0.42 },
      { radius: 2.68, y: 0.72, tilt: [0.46, 0.28, 0.12], color: 0x1e737f, opacity: 0.32 },
    ];

    for (const setting of bandSettings) {
      const bandPoints: THREE.Vector3[] = [];
      for (let i = 0; i < 128; i += 1) {
        const angle = (i / 128) * Math.PI * 2;
        bandPoints.push(
          new THREE.Vector3(
            Math.cos(angle) * setting.radius,
            Math.sin(angle) * setting.radius * 0.31 + setting.y,
            Math.sin(angle) * setting.radius * 0.72,
          ),
        );
      }
      const geometry = new THREE.BufferGeometry().setFromPoints(bandPoints);
      const material = new THREE.LineBasicMaterial({
        color: setting.color,
        transparent: true,
        opacity: setting.opacity,
        depthWrite: false,
      });
      const band = new THREE.LineLoop(geometry, material);
      band.rotation.set(setting.tilt[0], setting.tilt[1], setting.tilt[2]);
      geometries.push(geometry);
      materials.push(material);
      orbitalGroup.add(band);
    }
    sceneRoot.add(orbitalGroup);

    const vaultGeometry = new THREE.IcosahedronGeometry(0.48, 1);
    const vaultMaterial = new THREE.MeshBasicMaterial({
      color: 0x47d9cc,
      wireframe: true,
      transparent: true,
      opacity: 0.34,
      depthWrite: false,
    });
    const vault = new THREE.Mesh(vaultGeometry, vaultMaterial);
    vault.position.y = 0.08;
    sceneRoot.add(vault);
    geometries.push(vaultGeometry);
    materials.push(vaultMaterial);

    const coreGeometry = new THREE.OctahedronGeometry(0.24, 0);
    const coreMaterial = new THREE.MeshBasicMaterial({
      color: 0x1a8e96,
      transparent: true,
      opacity: 0.2,
      depthWrite: false,
    });
    const core = new THREE.Mesh(coreGeometry, coreMaterial);
    core.position.y = 0.08;
    sceneRoot.add(core);
    geometries.push(coreGeometry);
    materials.push(coreMaterial);

    const recordGeometry = new THREE.PlaneGeometry(2.35, 1.42, 1, 1);
    const draftMaterial = new THREE.ShaderMaterial({
      uniforms: {
        uTime: { value: STATIC_TIME },
        uStage: { value: 0 },
      },
      vertexShader: recordVertexShader,
      fragmentShader: recordFragmentShader,
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const confirmedMaterial = draftMaterial.clone();
    confirmedMaterial.uniforms.uStage.value = 1;
    const draftPlane = new THREE.Mesh(recordGeometry, draftMaterial);
    const confirmedPlane = new THREE.Mesh(recordGeometry, confirmedMaterial);
    draftPlane.position.set(-2.12, 0.38, 0.30);
    draftPlane.rotation.set(0.08, 0.38, -0.07);
    confirmedPlane.position.set(2.08, 0.24, 0.42);
    confirmedPlane.rotation.set(-0.06, -0.38, 0.06);
    sceneRoot.add(draftPlane, confirmedPlane);
    geometries.push(recordGeometry);
    materials.push(draftMaterial, confirmedMaterial);

    const transferGeometry = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-1.28, 0.35, 0.90),
      new THREE.Vector3(1.25, 0.35, 0.90),
    ]);
    const transferMaterial = new THREE.LineBasicMaterial({
      color: 0x2ca7a9,
      transparent: true,
      opacity: 0.22,
      depthWrite: false,
    });
    sceneRoot.add(new THREE.Line(transferGeometry, transferMaterial));
    geometries.push(transferGeometry);
    materials.push(transferMaterial);

    const observationGeometry = new THREE.SphereGeometry(0.075, 16, 12);
    const confirmationMaterial = new THREE.MeshBasicMaterial({
      color: 0x78f4df,
      transparent: true,
      opacity: 0.85,
      depthWrite: false,
    });
    const confirmationBead = new THREE.Mesh(observationGeometry, confirmationMaterial);
    sceneRoot.add(confirmationBead);
    materials.push(confirmationMaterial);

    const chartGroup = new THREE.Group();
    chartGroup.position.set(0, -1.62, 1.12);
    const chartGeometry = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-1.22, -0.08, 0),
      new THREE.Vector3(1.22, 0.16, 0),
    ]);
    const chartMaterial = new THREE.LineBasicMaterial({
      color: 0x62ead8,
      transparent: true,
      opacity: 0.76,
      depthWrite: false,
    });
    chartGroup.add(new THREE.Line(chartGeometry, chartMaterial));
    geometries.push(chartGeometry);
    materials.push(chartMaterial);

    const chartPointMaterial = new THREE.MeshBasicMaterial({
      color: 0x8af7e5,
      transparent: true,
      opacity: 0.94,
      depthWrite: false,
    });
    const firstObservation = new THREE.Mesh(observationGeometry, chartPointMaterial);
    const secondObservation = new THREE.Mesh(observationGeometry, chartPointMaterial);
    firstObservation.position.set(-1.22, -0.08, 0);
    secondObservation.position.set(1.22, 0.16, 0);
    chartGroup.add(firstObservation, secondObservation);
    sceneRoot.add(chartGroup);
    geometries.push(observationGeometry);
    materials.push(chartPointMaterial);

    const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    const finePointerQuery = window.matchMedia('(pointer: fine)');
    let reducedMotion = motionQuery.matches;
    let disposed = false;
    let inViewport = true;
    let animationFrame = 0;
    let frameCount = 0;
    let staticFrameRendered = false;
    let baseCameraZ = 10;
    let lastFrameTime = performance.now();
    let pointerTargetX = 0;
    let pointerTargetY = 0;
    let pointerCurrentX = 0;
    let pointerCurrentY = 0;
    let pointerBound = false;

    let resizeObserver: ResizeObserver | null = null;
    let intersectionObserver: IntersectionObserver | null = null;
    let mutationObserver: MutationObserver | null = null;
    let usingWindowResize = false;

    const pause = (): void => {
      if (animationFrame !== 0) {
        window.cancelAnimationFrame(animationFrame);
        animationFrame = 0;
      }
    };

    const cleanup = (): void => {
      if (disposed) return;
      disposed = true;
      pause();
      resizeObserver?.disconnect();
      intersectionObserver?.disconnect();
      mutationObserver?.disconnect();
      if (usingWindowResize) window.removeEventListener('resize', resize);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      canvas.removeEventListener('webglcontextlost', onContextLost);
      canvas.removeEventListener('webglcontextrestored', onContextRestored);
      motionQuery.removeEventListener('change', onMotionPreferenceChange);
      finePointerQuery.removeEventListener('change', updatePointerBinding);
      unbindPointer();
      for (const geometry of geometries) geometry.dispose();
      for (const material of materials) material.dispose();
      renderer.dispose();
    };

    const updateCamera = (now: number, animate: boolean): void => {
      if (!animate) {
        pointerCurrentX = 0;
        pointerCurrentY = 0;
      } else {
        const elapsed = Math.min(100, Math.max(0, now - lastFrameTime));
        const damping = 1 - Math.exp(-elapsed / 180);
        pointerCurrentX += (pointerTargetX - pointerCurrentX) * damping;
        pointerCurrentY += (pointerTargetY - pointerCurrentY) * damping;
      }
      camera.position.set(
        pointerCurrentX * 0.34,
        1.2 + pointerCurrentY * 0.18,
        baseCameraZ,
      );
      camera.lookAt(lookAtTarget);
      lastFrameTime = now;
    };

    const updateScene = (time: number, now: number, animate: boolean): void => {
      particleMaterial.uniforms.uTime.value = time;
      draftMaterial.uniforms.uTime.value = time;
      confirmedMaterial.uniforms.uTime.value = time;

      sceneRoot.rotation.y = Math.sin(time * 0.075) * 0.055;
      sceneRoot.rotation.x = Math.cos(time * 0.06) * 0.018;
      orbitalGroup.rotation.y = time * 0.026;
      orbitalGroup.rotation.z = Math.sin(time * 0.07) * 0.035;
      vault.rotation.y = time * 0.11;
      vault.rotation.x = time * 0.055;
      core.rotation.y = -time * 0.09;
      draftPlane.rotation.z = -0.07 + Math.sin(time * 0.18) * 0.018;
      confirmedPlane.rotation.z = 0.06 + Math.cos(time * 0.16) * 0.015;

      const transferPhase = (time * 0.115) % 1;
      const travel = THREE.MathUtils.smoothstep(transferPhase, 0.12, 0.78);
      const arrival = THREE.MathUtils.smoothstep(transferPhase, 0.68, 0.88);
      const fadeIn = THREE.MathUtils.smoothstep(transferPhase, 0.0, 0.10);
      const fadeOut = 1 - THREE.MathUtils.smoothstep(transferPhase, 0.88, 1.0);
      confirmationBead.position.set(
        THREE.MathUtils.lerp(-1.28, 1.25, travel),
        0.35 + Math.sin(travel * Math.PI) * 0.22,
        0.92,
      );
      confirmationBead.scale.setScalar(1 + arrival * 0.85);
      confirmationMaterial.opacity = (0.34 + arrival * 0.58) * fadeIn * fadeOut;

      const observationPulse = 0.92 + Math.sin(time * 0.9) * 0.08;
      firstObservation.scale.setScalar(observationPulse);
      secondObservation.scale.setScalar(1.04 - (observationPulse - 0.92));
      updateCamera(now, animate);
    };

    const requestRender = (): void => {
      if (
        disposed ||
        animationFrame !== 0 ||
        document.visibilityState === 'hidden' ||
        !inViewport ||
        (reducedMotion && staticFrameRendered)
      ) {
        return;
      }
      animationFrame = window.requestAnimationFrame(renderFrame);
    };

    function renderFrame(now: number): void {
      animationFrame = 0;
      if (disposed || document.visibilityState === 'hidden' || !inViewport) return;

      try {
        updateScene(reducedMotion ? STATIC_TIME : now * 0.001, now, !reducedMotion);
        renderer.render(scene, camera);
        frameCount += 1;
        canvas.dataset.webglReady = 'true';
        canvas.dataset.frame = String(frameCount);
        staticFrameRendered = reducedMotion;
      } catch {
        canvas.dataset.webglReady = 'false';
        pause();
        return;
      }

      if (!reducedMotion) requestRender();
    }

    function resize(): void {
      if (disposed) return;
      const bounds = canvas.getBoundingClientRect();
      const width = Math.max(1, Math.round(bounds.width || canvas.clientWidth || 1));
      const height = Math.max(1, Math.round(bounds.height || canvas.clientHeight || 1));
      const pixelRatio = Math.min(window.devicePixelRatio || 1, MAX_DPR);
      renderer.setPixelRatio(pixelRatio);
      renderer.setSize(width, height, false);
      particleMaterial.uniforms.uPixelRatio.value = pixelRatio;
      camera.aspect = width / height;
      baseCameraZ = 10 * Math.max(1, 0.9 / camera.aspect);
      camera.updateProjectionMatrix();
      staticFrameRendered = false;
      requestRender();
    }

    function onVisibilityChange(): void {
      if (document.visibilityState === 'hidden') pause();
      else requestRender();
    }

    function onPointerMove(event: PointerEvent): void {
      if (reducedMotion) return;
      pointerTargetX = (event.clientX / Math.max(1, window.innerWidth)) * 2 - 1;
      pointerTargetY = 1 - (event.clientY / Math.max(1, window.innerHeight)) * 2;
    }

    function onPointerLeave(): void {
      pointerTargetX = 0;
      pointerTargetY = 0;
    }

    function unbindPointer(): void {
      if (!pointerBound) return;
      window.removeEventListener('pointermove', onPointerMove);
      document.removeEventListener('pointerleave', onPointerLeave);
      pointerBound = false;
      pointerTargetX = 0;
      pointerTargetY = 0;
    }

    function updatePointerBinding(): void {
      if (!reducedMotion && finePointerQuery.matches && !disposed) {
        if (!pointerBound) {
          window.addEventListener('pointermove', onPointerMove, { passive: true });
          document.addEventListener('pointerleave', onPointerLeave, { passive: true });
          pointerBound = true;
        }
      } else {
        unbindPointer();
      }
    }

    function onMotionPreferenceChange(): void {
      reducedMotion = motionQuery.matches;
      staticFrameRendered = false;
      pointerTargetX = 0;
      pointerTargetY = 0;
      updatePointerBinding();
      pause();
      requestRender();
    }

    function onContextLost(event: Event): void {
      event.preventDefault();
      canvas.dataset.webglReady = 'false';
      pause();
    }

    function onContextRestored(): void {
      staticFrameRendered = false;
      resize();
    }

    document.addEventListener('visibilitychange', onVisibilityChange);
    canvas.addEventListener('webglcontextlost', onContextLost);
    canvas.addEventListener('webglcontextrestored', onContextRestored);
    motionQuery.addEventListener('change', onMotionPreferenceChange);
    finePointerQuery.addEventListener('change', updatePointerBinding);
    updatePointerBinding();

    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(resize);
      resizeObserver.observe(canvas);
    } else {
      usingWindowResize = true;
      window.addEventListener('resize', resize, { passive: true });
    }

    if (typeof IntersectionObserver !== 'undefined') {
      intersectionObserver = new IntersectionObserver(
        (entries) => {
          const entry = entries[entries.length - 1];
          inViewport = Boolean(entry?.isIntersecting);
          if (inViewport) requestRender();
          else pause();
        },
        { rootMargin: '80px' },
      );
      intersectionObserver.observe(canvas);
    }

    mutationObserver = new MutationObserver(() => {
      if (!canvas.isConnected) cleanup();
    });
    if (document.documentElement) {
      mutationObserver.observe(document.documentElement, { childList: true, subtree: true });
    }

    resize();
  } catch {
    for (const geometry of geometries) geometry.dispose();
    for (const material of materials) material.dispose();
    renderer.dispose();
    canvas.dataset.webglReady = 'false';
  }
}
