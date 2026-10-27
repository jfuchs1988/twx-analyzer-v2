// @ts-check
// ESLint flat config (runs in the node container, see scripts/lint.sh).
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      // Keep the established Rules-of-Hooks policy. The latest plugin's full
      // recommended preset also enables React Compiler diagnostics that are
      // not part of this application's current lint policy.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // the codebase intentionally casts loose JSON payloads to typed DTOs
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
