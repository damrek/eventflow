/**
 * A small, dependency-free summary: k6's own `textSummary` lives on jslib.k6.io, and a load test
 * that needs the internet to print its numbers is not reproducible offline. The complete result
 * object is written next to it as JSON, so nothing is lost by keeping this short.
 */
export function summaryText(data) {
  const lines = [];
  const duration = data.state?.testRunDurationMs ?? 0;
  lines.push(`scenario: ${__ENV.SCENARIO ?? "unknown"}`);
  lines.push(`run duration: ${(duration / 1000).toFixed(1)} s`);

  const thresholds = Object.entries(data.metrics)
    .filter(([, metric]) => metric.thresholds && Object.keys(metric.thresholds).length > 0)
    .flatMap(([name, metric]) =>
      Object.entries(metric.thresholds).map(
        ([expression, threshold]) =>
          `threshold ${threshold.ok ? "PASS" : "FAIL"} ${name}: ${expression}`,
      ),
    );
  lines.push(...thresholds);

  const metric = (name, key) => data.metrics[name]?.values?.[key];
  lines.push(`iterations: ${metric("iterations", "count") ?? 0}`);
  lines.push(`requests: ${metric("http_reqs", "count") ?? 0}`);
  lines.push(`failed requests: ${((metric("http_req_failed", "rate") ?? 0) * 100).toFixed(2)} %`);
  const latency = [
    ["avg", metric("http_req_duration", "avg")],
    ["p95", metric("http_req_duration", "p(95)")],
    ["p99", metric("http_req_duration", "p(99)")],
    ["max", metric("http_req_duration", "max")],
  ]
    .filter(([, value]) => value !== undefined)
    .map(([label, value]) => `${label} ${value.toFixed(1)} ms`)
    .join(", ");
  lines.push(`latency: ${latency}`);
  for (const [name, key] of [
    ["holds_created", "count"],
    ["holds_refused", "count"],
    ["holds_other", "count"],
    ["final_available", "value"],
    ["final_held", "value"],
    ["final_sold", "value"],
  ]) {
    const value = metric(name, key);
    if (value !== undefined) lines.push(`${name}: ${value}`);
  }
  return `${lines.filter((line) => line !== "").join("\n")}\n`;
}
