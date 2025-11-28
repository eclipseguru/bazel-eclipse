// .vscode-test.js
const { defineConfig } = require('@vscode/test-cli');

module.exports = defineConfig([
  {
    installExtensions: ['redhat.java@prerelease'],
    workspaceFolder: 'test/projects/small',
    files: 'out/test/**/*.test.js',
    mocha: {
      ui: 'tdd',
      timeout: 20000,
      reporter: 'mochawesome',
      reporterOptions: { 
        output: './test/result/extension.test.json',
        reportDir: './test/result',
        reportFilename: "[status]_[datetime]-[name]-report",
        reportTitle: 'Bazel Eclipse Extension Tests',
        reportPageTitle: 'Bazel Eclipse Extension Tests',
      },
    }
  }
]);
