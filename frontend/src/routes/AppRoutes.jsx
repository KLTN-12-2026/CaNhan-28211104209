import RentalGuidePage from "../pages/guest/RentalGuidePage";
import { PaymentReturnPage } from "../components/user/BookingDeposit";
import ApiVehicleFavoritesPage from "../pages/api/ApiVehicleFavoritesPage";
import ApiDashboardPage from "../pages/api/ApiDashboardPage";
import ApiPaymentsPage from "../pages/api/ApiPaymentsPage";
import ApiVehicleComparePage from "../pages/api/ApiVehicleComparePage";
import ApiVehicleCatalogPage from "../pages/api/ApiVehicleCatalogPage";
import { Navigate, Route, Routes } from "react-router-dom";
import AdminLayout from "../layouts/AdminLayout";
import GuestLayout from "../layouts/GuestLayout";
import StaffLayout from "../layouts/StaffLayout";
import UserLayout from "../layouts/UserLayout";
import HomePage from "../pages/guest/HomePage";
import LoginPage from "../pages/guest/LoginPage";
import RegisterPage from "../pages/guest/RegisterPage";
import AccountsPage from "../pages/admin/AccountsPage";
import CatalogPage from "../pages/admin/CatalogPage";
import DashboardPage from "../pages/admin/DashboardPage";
import LocationsPage from "../pages/admin/LocationsPage";
import ReviewsModerationPage from "../pages/admin/ReviewsModerationPage";
import VehiclesManagePage from "../pages/admin/VehiclesManagePage";
import BookingQueuePage from "../pages/staff/BookingQueuePage";
import FleetStatusPage from "../pages/staff/FleetStatusPage";
import HandoverPage from "../pages/staff/HandoverPage";
import ReturnPage from "../pages/staff/ReturnPage";
import ItineraryPlannerPage from "../pages/shared/ItineraryPlannerPage";
import NotFoundPage from "../pages/shared/NotFoundPage";
import RecommendPage from "../pages/shared/RecommendPage";
import UnauthorizedPage from "../pages/shared/UnauthorizedPage";
import VehicleDetailPage from "../pages/shared/VehicleDetailPage";
import VehicleListPage from "../pages/shared/VehicleListPage";
import MyBookingsPage from "../pages/user/MyBookingsPage";
import ProfilePage from "../pages/user/ProfilePage";
import SavedItinerariesPage from "../pages/user/SavedItinerariesPage";
import { ROLES } from "../utils/roles";
import ProtectedRoute from "./ProtectedRoute";
import { DataGate } from "../components/common/PageKit";
import { DEMO_MODE } from "../demo/config";
import DemoAccess from "../pages/guest/DemoAccess";
import NewBookingPage from "../pages/user/NewBookingPage";
import {
  ApiBookingDetailPage,
  ApiBookingListPage,
  ApiDocumentPage,
  ApiHomePage,
  ApiNewBookingPage,
  ApiOperationPage,
  ApiProfilePage,
  ApiVehicleDetailPage,
  ApiVehicleListPage,
} from "../pages/api/ApiScreens";
import {
  ApiAccountsPage,
  ApiBranchesPage,
  ApiReviewsAdminPage,
  ApiVehiclesAdminPage,
} from "../pages/api/ApiManagementScreens";
import ApiLocationsPage from "../pages/api/ApiLocationsPage";
import ApiItineraryPlannerPage from "../pages/api/ApiItineraryPlannerPage";
import ApiSavedItinerariesPage from "../pages/api/ApiSavedItinerariesPage";
import ApiItineraryDetailPage from "../pages/api/ApiItineraryDetailPage";
import {
  BookingDetailScreen,
  BookingListScreen,
  DocumentScreen,
  OperationScreen,
} from "../pages/shared/BookingScreens";
import { ItineraryDetailScreen } from "../pages/shared/AiScreens";

const screen = (element, apiElement = null) =>
  DEMO_MODE ? (
    <DataGate>{element}</DataGate>
  ) : apiElement || <DataGate>{element}</DataGate>;

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<GuestLayout />}>
        <Route path="/rental-guide" element={<RentalGuidePage />} />
        <Route path="/vehicles/favorites" element={DEMO_MODE ? <Navigate to="/vehicles" replace /> : <ApiVehicleFavoritesPage />} />
        <Route path="/vehicles/compare" element={DEMO_MODE ? <Navigate to="/vehicles" replace /> : <ApiVehicleComparePage />} />
        <Route path="/" element={DEMO_MODE ? <HomePage /> : <ApiHomePage />} />
        <Route
          path="/vehicles"
          element={screen(<VehicleListPage />, <ApiVehicleListPage />)}
        />
        <Route
          path="/vehicles/:vehicleId"
          element={screen(
            <VehicleDetailPage />,
            <ApiVehicleDetailPage />,
          )}
        />
        <Route path="/ai/recommend" element={screen(<RecommendPage />)} />
        <Route
          path="/ai/itinerary"
          element={screen(<ItineraryPlannerPage />, <ProtectedRoute roles={[ROLES.USER]}><ApiItineraryPlannerPage /></ProtectedRoute>)}
        />
        <Route
          path="/login"
          element={DEMO_MODE ? <DemoAccess /> : <LoginPage />}
        />
        <Route
          path="/register"
          element={DEMO_MODE ? <DemoAccess register /> : <RegisterPage />}
        />
        <Route path="/unauthorized" element={<UnauthorizedPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>

      <Route
        path="/user"
        element={
          <ProtectedRoute roles={[ROLES.USER]}>
            <UserLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="bookings" replace />} />
        {!DEMO_MODE && <Route path="payment-return" element={<PaymentReturnPage />} />}
        <Route
          path="bookings"
          element={screen(
            <MyBookingsPage />,
            <ApiBookingListPage area="user" />,
          )}
        />
        <Route
          path="bookings/new"
          element={screen(<NewBookingPage />, <ApiNewBookingPage />)}
        />
        <Route
          path="bookings/:bookingId"
          element={screen(
            <BookingDetailScreen area="user" />,
            <ApiBookingDetailPage area="user" />,
          )}
        />
        <Route
          path="bookings/:bookingId/contract"
          element={screen(
            <DocumentScreen area="user" kind="contract" />,
            <ApiDocumentPage area="user" kind="contract" />,
          )}
        />
        <Route
          path="bookings/:bookingId/invoice"
          element={screen(
            <DocumentScreen area="user" kind="invoice" />,
            <ApiDocumentPage area="user" kind="invoice" />,
          )}
        />
        <Route path="itineraries" element={screen(<SavedItinerariesPage />, <ApiSavedItinerariesPage />)} />
        <Route
          path="itineraries/:itineraryId"
          element={screen(<ItineraryDetailScreen />, <ApiItineraryDetailPage />)}
        />
        <Route
          path="profile"
          element={screen(<ProfilePage />, <ApiProfilePage />)}
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>

      <Route
        path="/staff"
        element={
          <ProtectedRoute roles={[ROLES.STAFF]}>
            <StaffLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="queue" replace />} />
        <Route
          path="queue"
          element={screen(
            <BookingQueuePage />,
            <ApiBookingListPage area="staff" />,
          )}
        />
        <Route
          path="bookings"
          element={<Navigate to="/staff/queue" replace />}
        />
        <Route
          path="bookings/:bookingId"
          element={screen(
            <BookingDetailScreen area="staff" />,
            <ApiBookingDetailPage area="staff" />,
          )}
        />
        <Route
          path="bookings/:bookingId/contract"
          element={screen(
            <DocumentScreen area="staff" kind="contract" />,
            <ApiDocumentPage area="staff" kind="contract" />,
          )}
        />
        <Route
          path="bookings/:bookingId/invoice"
          element={screen(
            <DocumentScreen area="staff" kind="invoice" />,
            <ApiDocumentPage area="staff" kind="invoice" />,
          )}
        />
        <Route
          path="handover"
          element={screen(
            <HandoverPage />,
            <ApiBookingListPage area="staff" mode="handover" />,
          )}
        />
        <Route
          path="handover/:bookingId"
          element={screen(
            <OperationScreen kind="handover" />,
            <ApiOperationPage kind="handover" />,
          )}
        />
        <Route
          path="return"
          element={screen(
            <ReturnPage />,
            <ApiBookingListPage area="staff" mode="return" />,
          )}
        />
        <Route
          path="return/:bookingId"
          element={screen(
            <OperationScreen kind="return" />,
            <ApiOperationPage kind="return" />,
          )}
        />
        <Route
          path="fleet"
          element={screen(<FleetStatusPage />, <ApiVehiclesAdminPage fleet />)}
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>

      <Route
        path="/admin"
        element={
          <ProtectedRoute roles={[ROLES.ADMIN]}>
            <AdminLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={screen(<DashboardPage />, <ApiDashboardPage />)} />
        <Route
          path="accounts"
          element={screen(<AccountsPage />, <ApiAccountsPage />)}
        />
        <Route
          path="vehicles"
          element={screen(<VehiclesManagePage />, <ApiVehiclesAdminPage />)}
        />
        <Route
          path="catalog"
          element={screen(<CatalogPage />, <ApiVehicleCatalogPage />)}
        />
        <Route
          path="locations"
          element={screen(<LocationsPage />, <ApiLocationsPage />)}
        />
        {!DEMO_MODE && <Route path="branches" element={<ApiBranchesPage />} />}
        {!DEMO_MODE && <Route path="payments" element={<ApiPaymentsPage />} />}
        <Route
          path="reviews"
          element={screen(<ReviewsModerationPage />, <ApiReviewsAdminPage />)}
        />
        <Route
          path="bookings"
          element={screen(
            <BookingListScreen area="admin" />,
            <ApiBookingListPage area="admin" />,
          )}
        />
        <Route
          path="bookings/:bookingId"
          element={screen(
            <BookingDetailScreen area="admin" />,
            <ApiBookingDetailPage area="admin" />,
          )}
        />
        <Route
          path="bookings/:bookingId/contract"
          element={screen(
            <DocumentScreen area="admin" kind="contract" />,
            <ApiDocumentPage area="admin" kind="contract" />,
          )}
        />
        <Route
          path="bookings/:bookingId/invoice"
          element={screen(
            <DocumentScreen area="admin" kind="invoice" />,
            <ApiDocumentPage area="admin" kind="invoice" />,
          )}
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
