import cv2
from ultralytics import YOLO

# 1. Load a pretrained fast YOLO model
model = YOLO("yolo11n.pt") 

# 2. Point to your downloaded dashcam video file
video_path = "dashcam.mp4" 
cap = cv2.VideoCapture(video_path)

# Verify video opened correctly
if not cap.isOpened():
    print(f"Error: Could not open video file {video_path}. Check the filename/path.")
    exit()

print("Processing dashcam footage... Press 'q' on your keyboard to quit.")

while cap.isOpened():
    success, frame = cap.read()
    if not success:
        print("Video processing finished or reached the end.")
        break

    # 3. Track vehicles. Classes: 2=car, 3=motorcycle, 5=bus, 7=truck
    # Using 'persist=True' forces the tracker to remember IDs across moving frames
    results = model.track(frame, persist=True, classes=[0, 2, 3, 5, 7], verbose=False)

    # 4. Draw the tracking boxes and IDs onto the video frame
    annotated_frame = results[0].plot()

    # 5. Display the real-time output window
    cv2.imshow("Moving Dashcam Vehicle Tracker", annotated_frame)

    # Press 'q' on your keyboard to exit the video preview early
    if cv2.waitKey(1) & 0xFF == ord("q"):
        break

# Clean up windows and release the video file
cap.release()
cv2.destroyAllWindows()
