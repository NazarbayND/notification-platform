/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#172026",
        mist: "#f5f7f8",
        line: "#d9e0e4",
        fern: "#2f6f5e",
        amber: "#b7791f",
        ruby: "#b42318"
      }
    }
  },
  plugins: []
};
