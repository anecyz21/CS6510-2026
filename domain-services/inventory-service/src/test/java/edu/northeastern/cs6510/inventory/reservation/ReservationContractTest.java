package edu.northeastern.cs6510.inventory.reservation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.contracts.Fixtures;
import edu.northeastern.cs6510.contracts.inventory.v1.InternalErrorV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.inventory.api.InternalApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Provider side of Inventory internal API v1: the reservation resource and its 409 body keep every fixture field (R16). */
@WebMvcTest(ReservationController.class)
class ReservationContractTest {
  private static final String ID = "0192c3f4-8b7a-7cc1-9a51-2f0e5d6c1a90";
  @Autowired MockMvc mvc;
  @MockBean ReservationService reservations;
  /** The low-stock controller nested in InventoryApplication is registered with the application class itself. */
  @MockBean JdbcTemplate db;

  @Test
  void reserveAcceptsTheFixtureRequestAndReturnsTheFixtureResource() throws Exception {
    ReservationV1 resource = Fixtures.JSON.treeToValue(Fixtures.read("reservation-v1.json"), ReservationV1.class);
    when(reservations.reserve(eq(ID), any())).thenReturn(new ReservationService.ReserveResult(resource, true));

    String body = mvc.perform(put("/internal/v1/reservations/" + ID).contentType("application/json")
            .content(Fixtures.withUnknownField("reserve-request-v1.json")))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    Fixtures.assertCovers(Fixtures.read("reservation-v1.json"), Fixtures.JSON.readTree(body), "reservation");
  }

  @Test
  void conflictCarriesTheReservationResource() throws Exception {
    ReservationV1 resource = Fixtures.JSON.treeToValue(Fixtures.read("reservation-v1.json"), ReservationV1.class);
    when(reservations.commit(ID)).thenThrow(new InternalApiException(HttpStatus.CONFLICT,
        new InternalErrorV1("RESERVATION_NOT_COMMITTABLE", "Reservation " + ID + " is RELEASED.", resource)));

    String body = mvc.perform(post("/internal/v1/reservations/" + ID + "/commit"))
        .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
    JsonNode error = Fixtures.JSON.readTree(body);
    Fixtures.assertCovers(Fixtures.JSON.readTree("{\"error\":\"\",\"message\":\"\"}"), error, "error");
    Fixtures.assertCovers(Fixtures.read("reservation-v1.json"), error.get("reservation"), "error.reservation");
  }
}
