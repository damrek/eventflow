import js from "@eslint/js";
import prettier from "eslint-config-prettier";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

/**
 * ESLint owns correctness (unused code, unsafe patterns, hook rules) and Prettier owns formatting.
 * The type checker is `tsc -b`; the type-aware lint rules are deliberately not enabled because the
 * build already fails on the same mistakes and CI should stay fast.
 */
const vitestGlobals = {
  afterAll: "readonly",
  afterEach: "readonly",
  beforeAll: "readonly",
  beforeEach: "readonly",
  describe: "readonly",
  expect: "readonly",
  it: "readonly",
  test: "readonly",
  vi: "readonly",
};

export default tseslint.config(
  { ignores: ["dist/", "coverage/", "node_modules/"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["src/**/*.{ts,tsx}"],
    plugins: { "react-hooks": reactHooks },
    rules: { ...reactHooks.configs.recommended.rules },
    languageOptions: {
      globals: { ...globals.browser },
    },
  },
  {
    files: ["src/test/**/*.{ts,tsx}", "src/**/*.test.{ts,tsx}"],
    languageOptions: {
      globals: { ...globals.browser, ...vitestGlobals },
    },
  },
  {
    files: ["vite.config.ts", "eslint.config.js"],
    languageOptions: {
      globals: { ...globals.node },
    },
  },
  prettier,
);
