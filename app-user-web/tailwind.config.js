/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#f2f6ff',
          100: '#dce6ff',
          200: '#b8cdff',
          300: '#93b4ff',
          400: '#709cff',
          500: '#4d83ff',
          600: '#3369e6',
          700: '#264fb3',
          800: '#1a367f',
          900: '#0e1d4c',
        },
      },
      boxShadow: {
        glass: '0 16px 40px rgba(15, 23, 42, 0.25)',
      },
      backdropBlur: {
        xs: '2px',
      },
    },
  },
  plugins: [],
}
