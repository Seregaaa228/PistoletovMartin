import contextlib
import io
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_aircraft_store_fit as fit


class AircraftStoreFitTest(unittest.TestCase):
    def test_loader_reproduces_measured_payload_centres(self):
        # The renderer's long-standing centre offsets were measured from SBW's baked models.
        models = fit.store_models(fit.ROOT / 'instance/mods')
        for kind, centre in zip(fit.SBW, (1.86134, 1.25737, 0.85467, 1.03125)):
            lo, hi = fit.extent(models[kind])
            self.assertAlmostEqual(-(lo[2] + hi[2]) / 2, centre, places=3, msg=kind)

    def test_every_store_hangs_clear_of_its_airframe(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = fit.main([])
        self.assertEqual(status, 0, out.getvalue())


if __name__ == '__main__':
    unittest.main()
