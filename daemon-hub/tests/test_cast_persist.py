from cybernomicon.app import record_spell_effects


def test_scry_cast_persists_devices(db):
    record_spell_effects(db, "scry", {"scope": "10.0.0.0/24", "devices": [
        {"address": "10.0.0.5", "hostname": "box", "status": "up",
         "open_ports": [22, 80]}]})
    devices = db.list_devices()
    assert any(d["address"] == "10.0.0.5" and d["open_ports"] == [22, 80]
               for d in devices)


def test_identify_cast_persists_services(db):
    db.upsert_device("10.0.0.5", open_ports=[22])
    record_spell_effects(db, "identify", {"address": "10.0.0.5", "services": [
        {"port": 22, "guess": "ssh", "banner": "x"}]})
    dev = db.get_device("10.0.0.5")
    assert dev["attributes"]["services"][0]["guess"] == "ssh"
