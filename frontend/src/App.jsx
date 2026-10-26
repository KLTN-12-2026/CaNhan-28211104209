import AssistantWidget from "./components/ai/AssistantWidget";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import ApiErrorBridge from "./routes/ApiErrorBridge";
import AppRoutes from "./routes/AppRoutes";
import { DemoProvider } from "./context/DemoContext";
import DemoToolbar from "./components/common/DemoToolbar";
import NavigationEffects from "./routes/NavigationEffects";

export default function App() {
  return (
    <AuthProvider>
      <DemoProvider>
        <BrowserRouter
          future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
        >
          <DemoToolbar />
          <ApiErrorBridge />
          <AppRoutes />
          <NavigationEffects />
          <AssistantWidget />
        </BrowserRouter>
      </DemoProvider>
    </AuthProvider>
  );
}
