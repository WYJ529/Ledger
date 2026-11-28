module.exports = async () => {
  const { default: base } = await import('./jest.config.js');
  return {
    ...base,
    coverageThreshold: {
      global: { statements: 100, branches: 100, functions: 100, lines: 100 },
    },
  };
};
