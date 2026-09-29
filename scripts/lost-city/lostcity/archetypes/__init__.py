"""Every DT-original Lost City building, in the order the generator writes them."""

from ..spec import Archetype
from . import (apartment_block, civic_hall, cooling_tower, fallen_block, hospital, hotel, leaning_tower, office_tower,
               overpass, petrol_station, radio_mast, railway_station, shopping_strip, silos, water_tower)

ALL: tuple[Archetype, ...] = (
    office_tower.ARCHETYPE,
    apartment_block.ARCHETYPE,
    hotel.ARCHETYPE,
    hospital.ARCHETYPE,
    civic_hall.ARCHETYPE,
    shopping_strip.ARCHETYPE,
    railway_station.ARCHETYPE,
    petrol_station.ARCHETYPE,
    radio_mast.ARCHETYPE,
    water_tower.ARCHETYPE,
    overpass.ARCHETYPE,
    cooling_tower.ARCHETYPE,
    silos.ARCHETYPE,
    leaning_tower.ARCHETYPE,
    fallen_block.ARCHETYPE,
)

BY_NAME = {a.spec.name: a for a in ALL}
