# Puppy locomotion and turns

The rig samples the repository’s 512 × 512 original standing illustration in `../../poses/Stand.png`. The face, chest, back, nearer front leg and nearer hind leg share one deforming coat surface. Two overlapping far-leg surfaces retain their order behind it throughout. Four fixed-length three-link chains drive the feet. The painted shanks bend around those chains, preserving the arc length of the fur centerline and the full width of each material cross-section. Each ankle and foot moves as one rigid patch. The original dark coat supplies a narrow overlap under the belly where the far thigh becomes visible.

The walk takes 24 frames and moves 78 original-canvas pixels. The diagonal trot covers 88 pixels in 16 frames. The ball chase keeps its separately authored three short diagonal steps per foot across 30 frames and travels 150 pixels. These clips retain at least two feet on the ground at every sampled frame. Right-heading locomotion reflects the evaluated left-heading geometry.

The full turn uses the registered side, three-quarter and front paintings in `../../poses/Turn01.png` through `Turn05.png`. During the front passage the tail and rear paws move behind the chest. Four separate foot trajectories and explicit front-paw overdraw handle crossings. The last painted right view passes into the same right neutral used by the walking clips.

## Rebuild

The renderer uses Python, NumPy, SciPy, Pillow, ModernGL, OpenCV and a desktop OpenGL 3.3 context. The checked render used Python 3.14 with NumPy 2.5.1, SciPy 1.18.0, Pillow 12.3.0, ModernGL 5.13.0 and OpenCV 5.1.0. Run the following commands in this directory, optionally setting `CUTEDOG_GAIT_OUTPUT` to an empty workspace before running them.

```sh
python locomotion.py
python turn.py
python check.py
python volume_audit.py
python index.py
```

`locomotion.py` evaluates the 16 left-heading motion paths and renders PNGs with their measured travel. `turn.py` builds and checks the three turn IDs. `check.py` creates right-heading frames and independently measures the rendered, visible sole markers. `index.py` verifies exact RGBA at motion boundaries and writes the mobile playback index.

## Playback

Each subdirectory contains inclusive samples from `00000.png` to its terminal frame. Play only the interval starts of a looping clip. The index provides the 24 fps interval count, entry and exit poses, heading, and motion distance per interval in fractions of the 512 px canvas. Positive distance follows the current heading. Backsteps have negative distance. The per-clip generated metadata and compact `foot-contacts.json` store unnormalized pixel values. The left-facing world contact coordinate is the local sole X minus cumulative forward displacement. The right-facing coordinate adds cumulative displacement.

`land` and `land_right` start at the authentic post-apex pose in frame 16 of their forward hop and end in neutral. `pause` holds weight on three paws as the fourth briefly lifts. `rebound` contains a stronger bounce followed by a smaller one. The slip has an intentional sliding interval and two staggered replants.

`qa.json` contains the actual raster-marker measurements, silhouette connectivity and flight clearance for the 13 originating motion paths. `turn-qa.json` records the sampled nose path, contour and exact source-anchor checks. For the walk, trot and chase, the maximum measured planted-paw drift between adjacent raster frames is 0.25, 0.50 and 0.71 source pixels, respectively. Only sufficiently visible sole markers are compared while planted. Completely occluded paws remain in the trajectory data.


`limb-metrics.json` records full-width and centerline measurements from the post-relaxation fur geometry, individual triangle area ratios, and material scale at the paws and ankles. `volume_audit.py` also verifies skeleton segment lengths and checks for inverted triangles on all four distal legs. The opaque ankle and paw retain unit local area and scale. The standalone hop, landing and rebound have their own aerial trajectories.
